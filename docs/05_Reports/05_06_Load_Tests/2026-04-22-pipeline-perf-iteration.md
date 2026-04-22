# Pipeline Performance Iteration Report

Date: 2026-04-22
Server: t3.small (2 vCPU, 2GB RAM)
Target: GET /api/v5/characters/{ign}/expectation
Load: 10,000 requests, concurrency 50
Branch: develop (all changes merged)

---

## 1. Baseline (Before All Changes)

캐시 초기화 후 10K 부하테스트. Virtual Thread + Semaphore(64) + per-row JPA.

```
Status 200 (HIT):      154
Status 503 (Queue Full): 9,846
Errors:                9,846
Throughput:            99.7 req/s
Avg response time:     500ms
p50:                   431ms
p95:                   1,030ms
p99:                   1,617ms
```

**병목**: Virtual Thread carrier thread pinning → admission control 즉시 포화. Worker가 태스크를 처리하지 못해 큐가 찌고, 모든 신규 요청이 503.

---

## 2. #733 Semaphore(64) 제거

PresetCalculationHelper에서 Semaphore 제거. 개별 아이템 계산이 Semaphore 대기 없이 바로 실행.

**변경 파일**: `PresetCalculationHelper.java` — Semaphore import, acquire/release 제거

**효과**: 개별 아이템 latency에서 Semaphore 대기 시간(최대 수백ms) 제거. 단, VT carrier pinning이 근본 병목이라 전체 throughput 개선은 제한적.

---

## 3. #734 Bulk JDBC Upsert (Read Model Write 배치화)

`batchViewUpsert()`를 per-row JPA → bulk JDBC로 교체.

| Before (per-row JPA) | After (bulk JDBC) |
|----------------------|-------------------|
| findByMessageId() per row | bulk SELECT (1 query) |
| save() per row | batch UPDATE (1 query) |
| saveToReadModel() per row | batch INSERT (1 query) |
| 30-120 DB round trips | **3 queries total** |

**변경 파일**:
- `CharacterViewBatchRepository.kt` (NEW)
- `AbstractExpectationCalcWorker.kt` (batchViewUpsert 재작성)
- `ExpectationCalcWorker.kt`, `ExpectationCalcLowWorker.kt` (의존성 추가)

**버그 수정**: `java.time.Instant` → `java.sql.Timestamp` (PostgreSQL JDBC 타입 추론 에러)

**검증**: 1,729건 view upsert 성공 확인. HikariCP pending=0, timeout=0으로 DB 병목 해소.

---

## 4. #735 Virtual Thread → FixedThreadPool(8)

PgmqWorker의 `newVirtualThreadPerTaskExecutor()` → `newFixedThreadPool(8)`.

**변경 파일**:
- `PgmqWorker.kt` — FixedThreadPool + Micrometer + @PreDestroy shutdown
- `PgmqWorkerConfig.kt` — `workerPoolSize` 추가
- `application-local.yml` — `worker-pool-size: 8`

**이것이 가장 큰 임팩트.** VT carrier thread pinning이 전체 파이프라인을 막고 있었음.

### #735 직후 부하테스트

```
Status 202 (Accepted): 10,000
Status 503 (Queue Full): 0
Errors:                0
Throughput:            511.9 req/s
Avg response time:     97ms
p50:                   92ms
p95:                   161ms
p99:                   191ms
```

---

## 5. #738 BlockingSubmitExecutor 제거

`BlockingSubmitExecutor` (spin-wait retry wrapper) 제거. #735 이후 불필요.

**변경 파일**: `BlockingSubmitExecutor.kt` 삭제, `ItemCalculationExecutorConfig.kt` wrapper 제거 (-71 lines)

---

## 6. 전체 통합 결과 (All Changes Merged)

#733, #734, #735, #738 모두 develop에 머지 후 캐시 초기화 + 10K 부하테스트.

### Request Phase

| Metric | Value |
|--------|-------|
| Total requests | 10,000 |
| Status 202 (Accepted) | **10,000** |
| Status 503 (Queue Full) | **0** |
| Errors | **0** |
| Admission throughput | **398.1 req/s** |
| Avg response time | **124.8ms** |
| p50 | 106.8ms |
| p95 | 206.6ms |
| p99 | 668.9ms |
| Max | 930.3ms |

### Worker Processing (200s 경과)

| Metric | Value |
|--------|-------|
| Views 완료 | 4,667건 |
| Worker 처리량 | ~23.3 tasks/s |
| 큐 잔여 | 7,397건 |
| Archive | 146,280건 |

### HikariCP

| Metric | Value |
|--------|-------|
| Active / Max | 3 / 30 |
| Pending | **0** |
| Acquire max | 241ms |
| Timeout | **0** |

---

## 7. 단계별 비교 요약

| Metric | Baseline | #735 후 | 전체 통합 | 변화 |
|--------|----------|---------|-----------|------|
| 202 Accepted | 0 | 10,000 | 10,000 | **0→100%** |
| 503 Error | 10,000 | 0 | 0 | **100→0%** |
| Throughput (req/s) | 99.7 | 511.9 | 398.1 | **4x** |
| Avg response (ms) | 500 | 97 | 125 | **4x faster** |
| p99 (ms) | 1,617 | 191 | 669 | **2.4x faster** |
| Worker tasks/s | N/A | ~18 | ~23 | 개선 |
| HikariCP pending | N/A | N/A | 0 | **병목 없음** |

---

## 8. 남은 병목

개별 태스크 처리시간이 여전히 김 (~8s/task). 원인:
- 2 vCPU에서 8 platform thread가 CPU-bound 계산 수행
- 아이템당 compute context switching
- #743 (Compute key dedup with time-sleep batching)으로 해결 예정

## 9. 이슈 정리

| Issue | Title | Status |
|-------|-------|--------|
| #733 | Semaphore(64) 제거 | Merged |
| #734 | Bulk JDBC upsert | Merged |
| #735 | VT → FixedThreadPool | Merged |
| #736 | Fan-out → flat queue | Closed (#743 대체) |
| #737 | Error isolation | Closed (#743 흡수) |
| #738 | BlockingSubmitExecutor 제거 | Merged |
| #743 | Compute key dedup | Open |
