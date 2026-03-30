# ADR-383: Compute-Bound Fan-Out 최적화 + Admission Control 활성화

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 제안됨 (Proposed) |
| 결정일 | 2026-03-28 |
| 결정자 | probabilistic-valuation-engine Team |
| 검토자 | Architecture Review Board |
| 선행 ADR | ADR-004 Collect-Compute-Serve, ADR-006 Scale-out, ADR-037 V5 CQRS, ADR-048 Virtual Threads |
| 관련 이슈 | #623 |
| 개정 이력 | v1: Sync→Async+CQRS 제안 → v2: Consensus Review 기반 재작성 (문제 정의 부정확) |

---

## 1. 배경 (Context)

### 기존 분석의 오류

v1에서 "Synchronous Fan-Out I/O + Read/Write 결합"을 문제로 정의했으나, **3에이전트 컨센서스 리뷰(Architect, Critic, Code-Reviewer) 결과 이 정의는 부정확했음**:

| v1 주장 | 실제 상태 | 근거 |
|---------|----------|------|
| "Synchronous Fan-Out" | 이미 `CompletableFuture` 비동기 | `EquipmentFetchProvider.kt:82-90` |
| "Circuit Breaker 미적용" | 이미 Resilience4j 전체 적용 | `ResilientNexonApiClient.kt` (CB + Bulkhead + Retry + TimeLimiter) |
| "Read/Write 결합" | 이미 Write-Behind Buffer로 분리 | `ExpectationWriteBackBuffer` (5초 flush) |
| "CQRS 미도입" | 이미 V5 CQRS 구현 | ADR-037 (PriorityCalculationQueue) |

### 실제 병목 (실측치)

`BOTTLENECK_ANALYSIS_20260324.md` 기준:

| 병목 영역 | 비중 | 상세 |
|-----------|------|------|
| **CPU** | **60%** | JSON 파싱 25-30%, 확률 DP O(n³) 15-20%, Gzip 8-12% |
| **Nexon API I/O** | 20% | 평균 150ms, 최대 572ms (비동기 + CB 적용됨) |
| **PostgreSQL upsert** | 15% | unique index 유지 + WAL + checkpoint |
| **Cache** | 5% | unique-key fan-out에 보호 없음 |

### 성능 현황

| 메트릭 | 값 | 비고 |
|--------|-----|------|
| Cache HIT RPS | 1515 | p99: 308ms |
| Cache MISS RPS | 230 | p99: 310ms |
| Virtual Thread RPS | 719 | ADR-048, Platform Thread 대비 8.1x |
| Nexon API Rate Limit | ~250 RPS | Semaphore 50 / 0.2s avg |

### 실제 문제

1. **Admission Control 비활성화**: 구현은 되어 있으나 `ratelimit.enabled: false`. Priority/Adaptive Admission 모두 off
2. **Fan-Out Explosion 위험**: 1,000 users × 1,000 characters = 1M unique keys → CPU 포화 + API Rate Limit 초과
3. **PostgreSQL Write Amplification**: unique index 유지 비용이 row 수에 비례. 15만→30만 rows 시 성능 저하
4. **CPU-Bound 계산**: 확률 DP O(n³)이 요청당 30-45회 호출, JSON 200-300KB 파싱

---

## 2. 결정 (Decision)

### 기존 인프라 최대 활용 + Compute 최적화

```
[현재] Request → Tomcat(Virtual Thread) → Async Fan-Out(CF) → CPU-Bound Compute → Write-Behind Buffer → Batch Upsert
                ↑                                                                                    ↑
                Admission Control OFF                                                                 Changed-only Upsert 없음

[개선] Request → Admission Control(활성화) → Async Fan-Out(CF) → CPU-Bound Compute(최적화) → Write-Behind Buffer → Changed-only Batch Upsert
              ↑                                                                                ↑
              max-in-flight=100                                                              dirty tracking
```

### 개선 전략

| 단계 | 변경 내용 | 대상 병목 |
|------|-----------|-----------|
| Phase 1 | Global Admission Control 활성화 | Fan-Out Explosion 방지 (P0) |
| Phase 2 | JSON 부분 파싱 + DTO 축소 | CPU 25-30% (P1) |
| Phase 3 | Changed-only upsert 도입 | PostgreSQL 15% (P1) |

---

## 3. 근거 (Rationale)

### Phase 1: Admission Control (P0)

| 항목 | 현재 (비활성화) | 활성화 후 |
|------|----------------|-----------|
| max-in-flight | 무제한 | 100 |
| max-queue-size | 무제한 | 1000 |
| Priority Admission | OFF | ON (HIGH/LOW 분리) |
| Adaptive Admission | OFF | ON (CPU 기반 자동 조절) |
| Fan-Out Explosion | 무방비 | Queue 초과 시 503 + Retry-After |

**근거**: `GlobalAdmissionControl` 컴포넌트는 이미 구현되어 있음. 설정 변경만으로 즉시 효과.

### Phase 2: CPU 최적화 (P1)

| 최적화 항목 | 현재 | 개선 방향 | 예상 효과 |
|------------|------|-----------|-----------|
| JSON 파싱 | 200-300KB 전체 파싱 | 필요 필드만 부분 파싱 | 25-30% → 10-15% |
| Gzip 압축 | 매 요청 | 캐시된 결과는 skip | 8-12% → 2-3% |
| 확률 DP | O(n³) 동기 | item 단위 병렬화 검토 | 프로파일링 후 판단 |

### Phase 3: Write 최적화 (P1)

| 항목 | 현재 | 개선 방향 | 예상 효과 |
|------|------|-----------|-----------|
| Upsert | 전체 row 갱신 | dirty tracking → 변경된 row만 | 30-50% write 감소 |
| Batch size | 100개/배치 | 튜닝 가능 (200-500) | 처리량 증가 |

### 수학적 근거 (수정 — 독립성 가정 제거)

```
# Fan-Out Explosion 시나리오 (Admission Control 없음)
QPS_effective = min(QPS_incoming, CPU_capacity / compute_per_request)
CPU_capacity = 100% → QPS_limit ≈ 230 (Cache MISS)
QPS_incoming > 230 → CPU 포화 → 모든 요청 지연

# Admission Control 활성화 시
QPS_effective = min(QPS_incoming, max_in_flight / avg_latency)
max_in_flight=100, avg_latency=0.5s → QPS_limit = 200 (안정)
초과 요청 → 503 Service Unavailable + Retry-After
```

> **참고**: v1의 `P(fail) = 1 - ∏(1 - P(upstream_i))` 공식은 모든 업스트림이 동일 Nexon API를 사용하므로 **독립 가정이 성립하지 않음**. 실제로는 공유 CircuitBreaker로 인해 장애가 상관관계를 가짐.

---

## 4. 결과 (Consequences)

### 긍정적

- **즉시 효과**: 설정 변경만으로 Fan-Out Explosion 방지 (Phase 1)
- **기존 인프라 활용**: Virtual Thread, Resilience4j, CQRS, Write-Behind Buffer 유지
- **점진적 개선**: Phase별 독립 적용 가능
- **ROI 확실**: Phase 1은 설정 변경만으로 즉시 효과, Phase 2-3은 프로파일링 기반 정밀 최적화

### 부정적 (한계)

- **CPU 최적화는 프로파일링 선행 필수**: 예상 효과는 실측 검증 필요
- **확률 DP 병렬화는 복잡**: 공유 상태 의존성으로 단순 병렬화 불가
- **Admission Control 활성화 시 일부 요청 503**: 사용자 경험 영향 → Retry-After 헤더로 완화

### Risk

- **Admission Control 임계값 튜닝**: 너무 낮으면 유휴 자원 낭비, 너무 높으면 보호 불충분 → A/B 테스트 필요
- **Changed-only upsert 구현 복잡도**: 이전/이후 값 비교 로직 추가 → LogicExecutor 내부로 격리

---

## 5. 구현 세부

### Phase 1: Global Admission Control 활성화

**목표**: Fan-Out Explosion 방지, max-in-flight 기반 Backpressure 확보

**Definition of Done:**
- [ ] `application.yml`에서 `admission-control.max-in-flight: 100` 활성화
- [ ] `priority-admission.enabled: true` 설정
- [ ] `adaptive-admission.enabled: true` 설정
- [ ] 부하 테스트: 500 RPS에서 503 응답 비율 < 5% (나머지는 정상 처리)
- [ ] 메트릭: `admission_rejected_total` 수집
- [ ] Chaos Test: 1000 RPS burst → max-in-flight 초과 요청이 503 + Retry-After 반환

**수정 파일:**

| 파일 | 변경 내용 |
|------|----------|
| `module-app/src/main/resources/application.yml` | `ratelimit.enabled: true`, admission 설정 활성화 |
| `module-app/.../GameCharacterControllerV4.kt` | Admission Control 적용 범위 확대 검토 |

### Phase 2: CPU 최적화

**목표**: JSON 파싱 CPU 점유율 25-30% → 10-15% 감소

**Definition of Done:**
- [ ] async-profiler로 JSON 파싱 hotspot 확인
- [ ] 필요 없는 필드 제외 (Jackson `@JsonIgnoreProperties` 또는 부분 파싱)
- [ ] 캐시 HIT 시 Gzip skip 로직 구현
- [ ] CPU 점유율 25-30% → 15% 이하 달성
- [ ] p99 latency 변동 < 10% (회귀 없음)

**수정 파일:**

| 파일 | 변경 내용 |
|------|----------|
| `module-infra/.../EquipmentStreamingParser.kt` | 부분 파싱 최적화 |
| `module-infra/.../GzipUtils.kt` | 캐시 HIT 시 skip 로직 |

### Phase 3: Changed-only Upsert

**목표**: Write 감소 30-50%

**Definition of Done:**
- [ ] 이전/이후 값 비교 (dirty tracking) 로직 구현
- [ ] 변경된 row만 upsert
- [ ] Write-behind buffer에 dirty flag 전파
- [ ] Write 감소율 ≥ 30% (프로덕션 메트릭)
- [ ] 데이터 정합성: 변경된 row가 누락 없이 저장됨

**수정 파일:**

| 파일 | 변경 내용 |
|------|----------|
| `module-app/.../ExpectationWriteBackBuffer.kt` | dirty tracking 로직 |
| `module-app/.../ExpectationPersistenceService.java` | 변경 감지 후 upsert |

---

## 6. 모니터링 & 검증

### 필수 메트릭

| 메트릭 | 목적 | 임계값 |
|--------|------|--------|
| `admission_rejected_total` | Admission Control 거부율 | < 5% (정상), > 20% (임계값 조정 필요) |
| `admission_in_flight` | 현재 처리 중 요청 수 | max-in-flight=100 이하 |
| `cpu_usage_percent` | CPU 사용률 | < 70% (Phase 2 목표) |
| `db_upsert_rows_total` | Upsert row 수 | Phase 3 전후 비교 (30-50% 감소 목표) |
| `write_behind_buffer_size` | Write-Behind Buffer 적체 | < 1000 (flush interval 이내 처리) |

### Prometheus Alert Rules

```yaml
groups:
  - name: adr030_optimization_alerts
    rules:
      - alert: AdmissionRejectRateHigh
        expr: rate(admission_rejected_total[5m]) / rate(admission_total[5m]) > 0.2
        for: 5m
        annotations:
          summary: "Admission Control 거부율 > 20% — max-in-flight 증설 검토"

      - alert: CpuUsageHigh
        expr: cpu_usage_percent > 80
        for: 10m
        annotations:
          summary: "CPU 사용률 > 80% — Phase 2 JSON 최적화 우선"
```

---

## 7. 관련 파일

### 수정 대상

| 파일 | 역할 | Phase |
|------|------|-------|
| `module-app/.../resources/application.yml` | Admission Control 설정 활성화 | 1 |
| `module-infra/.../provider/EquipmentFetchProvider.kt` | 팬아웃 (이미 비동기, 수정 불필요) | — |
| `module-infra/.../external/impl/ResilientNexonApiClient.kt` | CB + Bulkhead (이미 적용, 수정 불필요) | — |
| `module-infra/.../EquipmentStreamingParser.kt` | JSON 부분 파싱 최적화 | 2 |
| `module-infra/.../GzipUtils.kt` | 캐시 HIT 시 Gzip skip | 2 |
| `module-app/.../persistence/ExpectationWriteBackBuffer.kt` | dirty tracking | 3 |
| `module-app/.../persistence/ExpectationPersistenceService.java` | changed-only upsert | 3 |
| `module-infra/.../admission/GlobalAdmissionControl.kt` | Admission Control (이미 구현) | 1 |

### 이미 구현됨 (수정 불필요)

| 파일 | 구현 상태 | 관련 ADR |
|------|-----------|----------|
| `EquipmentFetchProvider.kt` | CompletableFuture 비동기 팬아웃 | ADR-004 |
| `ResilientNexonApiClient.kt` | Resilience4j 전체 적용 | ADR-006 |
| `PriorityCalculationQueue` | V5 CQRS Command Side | ADR-037 |
| Virtual Thread 설정 | Java 21 + Spring Boot 3.5.4 | ADR-048 |

### 참조 문서

| 문서 | 내용 |
|------|------|
| `docs/analysis/BOTTLENECK_ANALYSIS_20260324.md` | 실측 병목 분석 |
| `docs/nexon-api-fanout-analysis.md` | 팬아웃 최적화 분석 |
| `docs/adr/029-like-direct-db-approach.md` | Like Direct DB (QPS < 100, 본 ADR과 무관) |
