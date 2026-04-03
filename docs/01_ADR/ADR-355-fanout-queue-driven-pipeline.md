# ADR-355: Fan-Out Queue-Driven Pipeline 전환

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 수락됨 (Accepted) |
| 결정일 | 2026-04-03 |
| 결정자 | probabilistic-valuation-engine Team |
| 선행 ADR | ADR-316 PGMQ Integration, ADR-317 Hexagonal Architecture |
| 관련 계획 | docs/09_Plans/2026-04-03-fanout-queue-driven-pipeline.md |

---

## 1. 배경 (Context)

### 문제 상황

V4 cold-path는 `GlobalAdmissionControl` 내부 worker가 직접 heavy work를 수행 (semi-sync). V5는 이미 PGMQ Queue-Driven이지만:
- Rate Limiter가 4곳에 분산 (Semaphore 50/30/50 + Bulkhead 50)
- Task 상태 조회 API 부재
- PGMQ polling 성능 미흡 (batch=10, interval=1s)
- `Semaphore.acquire()` 사용으로 Virtual Thread carrier pinning 위험

### 핵심 진단

| 항목 | 상태 |
|------|------|
| V5 cold-path | PGMQ 기반 (이미 Queue-Driven) |
| V4 cold-path | AdmissionControl worker 직접 처리 (semi-sync) |
| Rate Limiter | 4곳 분산 (MetricsWrapper Semaphore(50), Bulkhead(50), FanOutBatchLoader Semaphore(30), GlobalAdmissionControl Semaphore(100)) |
| Task 상태 조회 | 없음 |
| PGMQ polling | batch=10, 1s interval (개선 여지) |

---

## 2. 결정 (Decision)

### V4는 Legacy 동기 유지, V5만 Queue-Driven 개선

V4 API는 Breaking Change 없이 동기 처리 유지. V5 API에만 다음 개선 적용:

1. **Task Receipt**: V5 cold-path → 202 + `X-Task-Id` 헤더 반환
2. **Rate Limiter 중앙 집중화**: 4곳 Semaphore → 단일 `NexonRateLimiter` (ReentrantLock)
3. **PGMQ 성능 개선**: batch=50, VT=300s, polling interval 감소
4. **Task 상태 조회 API**: `GET /api/v5/characters/{ign}/task/{taskId}` (PostgreSQL source of truth)
5. **Observability**: Queue depth, Worker batch metrics

---

## 3. 대안 (Alternatives)

| 대안 | 장점 | 단점 | 선택 |
|------|------|------|------|
| V4도 Queue-Driven 전환 | 전체 API 비동기화 | Breaking Change, 클라이언트 마이그레이션 필요 | 기각 |
| V4 Legacy 유지 + V5만 개선 | Zero Breaking Change | V4 한계 존속 | **채택** |
| Semaphore 유지 | 단순 | VT carrier pinning, 분산 관리 | 기각 |
| ReentrantLock 통합 | VT 안전, 중앙 집중 | JVM-local (scale-out 시 별도 작업) | **채택** |
| PGMQ archive 기반 Task 조회 | Queue-native | Archive cleanup(30일) 후 NOT_FOUND | 기각 |
| PostgreSQL 기반 Task 조회 | 영구 보존 | Archive 활용 불가 | **채택** |

---

## 4. 구현 계획 (Implementation)

### Phase별 독립 배포

```
Phase 4 (PGMQ Perf)  → Phase 1 (Task Receipt)  → Phase 5 (Task API)
→ Phase 3 (Rate Limiter)  → Phase 6 (Metrics)  → Phase 2 (V4 Javadoc)
```

각 Phase는 독립 배포 가능. V4 API 변경 없음.

### 신규 파일

| 파일 | 모듈 | 설명 |
|------|------|------|
| `TaskReceipt.java` | module-app | TaskReceipt record (DIP 준수) |
| `TaskStatusPort.kt` | module-core | Task 상태 조회 Port |
| `TaskStatus.kt` | module-core | Task 상태 Enum |
| `NexonRateLimiter.kt` | module-infra | 중앙 Rate Limiter (ReentrantLock) |
| `TaskStatusService.java` | module-app | Task 상태 조회 구현 (PostgreSQL 우선) |
| `TaskStatusController.kt` | module-web | Task 상태 REST API |
| `QueueMetrics.kt` | module-infra | Queue depth metrics |

### 수정 파일

| 파일 | 변경 |
|------|------|
| `ExpectationCalculationQueue.java` | `offerWithReceipt()` + `@Transactional(REQUIRES_NEW)` |
| `CalculationQueuePortAdapter.java` | `offerHighPriorityWithReceipt()` 추가 |
| `GameCharacterControllerV5.kt` | X-Task-Id 헤더 반환 |
| `MetricsNexonApiClientWrapper.kt` | Semaphore → NexonRateLimiter |
| `NexonFanOutBatchLoader.kt` | Semaphore → NexonRateLimiter |
| `PgmqClient.kt` | `isArchived()` 메서드 추가 |
| `PgmqWorker.kt` | 배치 단위 aggregate metrics |
| `maple-infra-defaults.properties` | batch, polling, VT 변경 |
| `GlobalAdmissionControl.kt` | V4-Only Javadoc |

---

## 5. 결과 (Consequences)

### 긍정적

- V5 throughput 향상 (batch 10→50, VT 30s→300s)
- 단일 Rate Limiter로 동시성 제어 단순화
- Virtual Thread 안전 (ReentrantLock)
- Task 상태 조회로 클라이언트 polling 가능
- V4 API Zero Breaking Change

### 부정적

- NexonRateLimiter JVM-local (scale-out 시 별도 분산 락 작업 필요)
- PGMQ batch 증가 시 Visibility Timeout 미조정 위험 (VT=300s로 완화)

### 제약

- 현재 1-2 인스턴스 운영이므로 JVM-local로 충분
- Scale-out 시 PostgreSQL Advisory Lock 전환 (별도 Issue)
