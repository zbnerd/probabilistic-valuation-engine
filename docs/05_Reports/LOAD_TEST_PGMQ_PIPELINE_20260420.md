# Load Test Report: PGMQ Pipeline (2026-04-20)

**Branch:** feature/pgmq-pipeline
**Profile:** local (Remote PostgreSQL - Vultr)
**Concurrency:** 50
**Total Requests:** 10,000 IGNs

---

## 1. Request Phase

| Metric | Value |
|--------|-------|
| Total requests | 10,000 |
| Duration | 129.1s |
| Throughput | 77.4 req/s |
| Status 202 (QUEUE) | 10,000 |
| Status 200 (HIT) | 0 |
| Errors | 0 |

### Response Time Distribution

| Percentile | Latency |
|------------|---------|
| Min | 30ms |
| p50 | 498ms |
| p95 | 1,656ms |
| p99 | 2,960ms |
| Max | 4,882ms |
| Avg | 644ms |

### Progress Timeline

| Progress | Sent | Avg(ms) | Elapsed |
|----------|------|---------|---------|
| 10% | 1,000 | 113.8ms | 2.4s |
| 20% | 2,000 | 286.9ms | 8.3s |
| 30% | 3,000 | 570.5ms | 19.7s |
| 40% | 4,000 | 565.5ms | 31.2s |
| 50% | 5,000 | 509.8ms | 41.2s |
| 60% | 6,000 | 700.0ms | 55.8s |
| 70% | 7,000 | 820.3ms | 72.1s |
| 80% | 8,000 | 990.7ms | 91.7s |
| 90% | 9,000 | 910.7ms | 109.8s |
| 100% | 10,000 | 973.8ms | 129.1s |

## 2. Queue Drain

- Max wait: 180s
- Result: **TIMEOUT** — queue not fully drained

### Queue State (mid-test snapshot)

| Queue | Count |
|-------|-------|
| calc_high_queue | 6,640 |
| retry_queue | 3,561 |
| valuation_views (completed) | 0 |

## 3. Server-Side Slow Task Analysis

### Slow Task Statistics (30,728 total slow task detections)

| Task | Count | Avg (ms) | Max (ms) |
|------|-------|----------|----------|
| V4:PresetJoin | 2,044 | 2,354 | 27,316 |
| Observability:Track:nexon.itemdata | 571 | 541 | 8,580 |
| Observability:Track:nexon.basic | 148 | 632 | 3,369 |
| AlertService:Critical (external API failure) | 99 | 517 | 7,214 |
| PgmqWorker:ProcessBatch:calc_high | 85 | 2,084 | 24,840 |
| ExpectationCalcWorker:PreWarm:calc_high | 84 | 2,040 | 24,105 |
| Parser:StreamingParse:preset2 | 80 | 560 | 5,405 |
| AdaptiveBatch:ProcessChunk:50 | 79 | 2,252 | 10,112 |
| Parser:StreamingParse:preset3 | 76 | 379 | 990 |
| Parser:StreamingParse:preset1 | 62 | 546 | 4,740 |
| PgmqWorker:DrainBuffer:calc_high | 43 | 690 | 3,285 |
| PgmqClient:Send:nexon_retry_queue | 43 | 461 | 2,011 |
| CubeService:CalculateDP:STR_PERCENT | 40 | 891 | 4,273 |
| CubeService:CalculateV1:ADDITIONAL | 36 | 407 | 1,305 |
| CubeService:CalculateDP:INT_PERCENT | 25 | 1,438 | 5,461 |
| CubeService:CalculateDP:HP_PERCENT | 20 | 1,392 | 8,256 |

## 4. Exception Analysis

| Exception | Count | Root Cause |
|-----------|-------|------------|
| BulkheadFullException | 22,198 | Nexon API bulkhead saturated (concurrent call limit) |
| ExternalServiceException | 21,064 | Nexon API failure propagated |
| EmptyResultDataAccessException | 10,000 | Cache miss on first lookup (expected) |
| CompletionException | 8,216 | Async Nexon API call failure wrapper |
| TaskRejectedException | 5,851 | Thread pool rejection under load |
| RejectedExecutionException | 5,478 | Executor capacity overflow |
| WebClientRequestException | 2,426 | HTTP client timeout / connection failure |
| PSQLException | 1,434 | PostgreSQL connection contention |

## 5. HikariCP Connection Pool

| Metric | Value |
|--------|-------|
| Pool max | 30 |
| Active (peak) | 29 |
| Pending (peak) | 73 |
| Acquire max | 225ms |

## 6. Key Findings

### Primary Bottleneck: Nexon API Bulkhead
- BulkheadFullException 22K건 — 동시 호출 제한으로 인한 포화
- 이 것이 V4:PresetJoin (avg 2.3s, max 27s) 병목으로 전파
- PgmqWorker 처리 속도도 연쇄 지연 (avg 2s, max 24s)

### Secondary Bottleneck: Connection Pool
- HikariCP pending=73 peak — 30 커넥션으로 부족
- PSQLException 1,434건 발생

### Cache Miss Storm
- EmptyResultDataAccessException 10,000건 — 전체 cold start
- 첫 요청은 전부 Nexon API 호출 필요

## 7. Improvement Recommendations

1. **Nexon API Bulkhead 한도 조정** — 현재 설정 대비 요청량 과다
2. **HikariCP pool size 증설** — pending=73은 심각한 병목
3. **V4:PresetJoin 최적화** — max 27초는 타임아웃 위험
4. **Queue drain 속도 개선** — 180초 내 10K건 처리 불가
