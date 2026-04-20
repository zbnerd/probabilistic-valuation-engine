# ADR-360: PGMQ Pipeline Load Test Performance Tuning

**Date:** 2026-04-20
**Status:** Accepted
**Load Test Report:** [LOAD_TEST_PGMQ_PIPELINE_20260420_R2.md](../05_Reports/LOAD_TEST_PGMQ_PIPELINE_20260420_R2.md)

---

## Context

PGMQ 기반 CQRS two-phase 파이프라인(calculatOnly → batchWrite)에 10,000건 부하테스트를 수행한 결과,
다수의 병목이 발생하여 워커가 정상 동작하지 않았다.

- API는 123.7 req/s로 수용하지만, 워커 드레인 속도는 2.8 items/s에 불과
- Major GC 1,193회 / 391초로 STW(Stop-The-World)가 워커 처리를 지속적으로 방해
- View table(character_valuation_views)에 0건 기록
- BulkheadFullException 75,678회 발생

---

## Decision

6개 병목을 순차적으로 식별하고 해결:

### 1. 파싱 3회 중복 → 1-pass 최적화

| 항목 | Before | After |
|------|--------|-------|
| 파싱 횟수 | 3-pass (length, list, map) | 1-pass |
| Throughput | ~87 req/s | ~124 req/s (+43%) |

**방법:** 한 번의 파싱으로 모든 필드를 추출하는 1-pass 파서 도입.

### 2. GC Humongous Object → G1HeapRegionSize=4m

| 항목 | Before | After |
|------|--------|-------|
| Humongous allocation | 2,142회 | 0회 |
| Young GC 총 시간 | 322s | 23s |

**원인:** 300KB JSON 응답이 직렬화 후 1-2MB Java 객체가 되어 G1 기본 region(1m)을 초과.
**해결:** `-XX:G1HeapRegionSize=4m` 설정으로 Humongous Object 방지.

### 3. Heap 부족 → 2GB → 8GB

| 항목 | 2GB | 8GB | 변화 |
|------|-----|-----|------|
| Major GC | 1,193회 / 391s | 38회 / 22.1s | **-97%** |
| 드레인 속도 | 2.8/s | 12.2/s | **+336%** |
| Old Gen 사용률 | 76% | 90% | 양호 (GC로 회복) |

**검증:** 16GB도 테스트했으나 성능 차이의 주원인이 GC가 아닌 Bulkhead임을 확인.
8GB가 비용 대비 최적. 서버 RAM 64GB로 충분.

**JVM 설정:**
```
-Xms8g -Xmx8g -XX:+UseG1GC -XX:G1HeapRegionSize=4m
```

### 4. View table 미기록 → JavaTimeModule 등록

| 항목 | Before | After |
|------|--------|-------|
| View 기록 | 0건 (JSR310 오류) | 3,811건 (100% 매치) |

**원인:** `ObjectMapper`에 `JavaTimeModule` 미등록으로 `LocalDateTime` 직렬화 실패.
**해결:** `ObjectMapper().registerModule(JavaTimeModule())` 추가.

### 5. Nexon API Bulkhead → 50/500ms 최적

| 설정 | BulkheadFull | SocketTimeout | 드레인 속도 |
|------|-------------|---------------|-------------|
| **50/500ms** | 75,678 | **0** | **12.2/s** |
| 50/5s | 0 | 23,058 | 8.3/s |
| 100/1s | 2,790 | 22,850 | 8.0/s |
| 200/2s | 0 | 3,435 | 8.0/s |

**결론:** Nexon API 안정 한계는 50 동시 호출. 초과 시 SocketTimeout 폭발.
- `BulkheadFullException`은 "빠른 거부" → PGMQ 큐 재시도 → 정상 동작
- `maxWaitDuration` 증가는 28s 타임아웃 예산을 잠식 → 역효과

### 6. HikariCP pending → 121 → 60 (-50%)

Heap 증가로 커넥션 대기가 자연스럽게 감소. 별도 튜닝 불필요.

---

## Final Results (8GB + Bulkhead 50/500ms)

| 항목 | 값 |
|------|-----|
| API Throughput | 123.7 req/s |
| Request phase | 80.8s |
| p50 응답시간 | 383ms |
| p95 응답시간 | 749ms |
| p99 응답시간 | 1,092ms |
| 워커 드레인 평균 | 12.2 items/s |
| 워커 드레인 최대 | 22.3 items/s |
| 180s 드레인 처리량 | 3,811건 |
| View 기록 | 3,811건 (archive 100% 매치) |
| Major GC | 38회 / 22.1s |
| Young GC | 1,583회 / 23.2s |

---

## Consequences

### Positive
- **워커 드레인 속도 +336%** (2.8 → 12.2 items/s)
- **Major GC -97%** (1,193 → 38회), STW로 인한 워커 정지 사실상 해소
- View table 100% 기록, CQRS Query side 정상 동작
- Nexon API 안정성 확보 (SocketTimeout 0)

### Remaining
- BulkheadFullException 75,678회 — Nexon API 한계로 현재 최적. PGMQ 재시도로 처리됨
- Old Gen 90% (8GB) — 드레인 후 GC로 회복되므로 운영 지장 없음
- 180초 내 전량 처리 불가 (3,811/10,000) — 워커 스케일아웃 또는 백프레셔로 개선 필요

### Risk
- 8GB 고정 힙은 10K 부하 기준. 트래픽 급증 시 재평가 필요
- Nexon API 한계(50 concurrent)는 외부 의존성으로 변경 불가
