# Slow Task Analysis — PGMQ Pipeline Load Test (2026-04-20)

**Source:** `module-app/logs/app.log` (851MB)
**Total slow task entries:** 85,902
**Unique task types:** 42 (count >= 5)

---

## Top 10 Slow Tasks

| Task | count | avg | p50 | p95 | p99 | max |
|------|-------|-----|-----|-----|-----|-----|
| **PgmqWorker:CalculateOnly:expectation_calc_high** | **54,661** | 9.3s | 5.9s | **28.6s** | 29.3s | 32.6s |
| FanOutBatchLoader:Fetch | 7,054 | 777ms | 743ms | 1.3s | 1.7s | 2.4s |
| V4:PresetJoin | 5,844 | 1.9s | 1.9s | 3.8s | 5.1s | 9.4s |
| AdaptiveBatch:FastLane | 1,853 | 1.0s | 949ms | 1.6s | 2.0s | 2.5s |
| AdvisoryLock:ElectLeader | 1,848 | 899ms | 852ms | 1.5s | 1.8s | 2.4s |
| Observability:Track:external.api.nexon.itemdata | 1,251 | 386ms | 265ms | 985ms | 1.1s | 1.2s |
| NexonApiPgmqProcessor:ProcessMessage | 423 | 477ms | 414ms | 983ms | 1.5s | 1.9s |
| PGMQ:PublishRetry | 371 | 389ms | 255ms | 1.0s | 1.2s | 1.5s |
| Observability:Track:external.api.nexon.basic | 367 | 320ms | 249ms | 762ms | 1.1s | 1.2s |
| ExpectationCalcWorker:PreWarm:expectation_calc_high | 225 | 1.4s | 1.4s | 2.1s | 2.5s | 2.6s |

---

## All Tasks (42 types)

| Task | count | avg | p50 | p95 | p99 | max |
|------|-------|-----|-----|-----|-----|-----|
| PgmqWorker:CalculateOnly:expectation_calc_high | 54,661 | 9.3s | 5.9s | 28.6s | 29.3s | 32.6s |
| FanOutBatchLoader:Fetch | 7,054 | 777ms | 743ms | 1.3s | 1.7s | 2.4s |
| V4:PresetJoin | 5,844 | 1.9s | 1.9s | 3.8s | 5.1s | 9.4s |
| AdaptiveBatch:FastLane | 1,853 | 1.0s | 949ms | 1.6s | 2.0s | 2.5s |
| AdvisoryLock:ElectLeader | 1,848 | 899ms | 852ms | 1.5s | 1.8s | 2.4s |
| Observability:Track:external.api.nexon.itemdata | 1,251 | 386ms | 265ms | 985ms | 1.1s | 1.2s |
| NexonApiPgmqProcessor:ProcessMessage | 423 | 477ms | 414ms | 983ms | 1.5s | 1.9s |
| PGMQ:PublishRetry | 371 | 389ms | 255ms | 1.0s | 1.2s | 1.5s |
| Observability:Track:external.api.nexon.basic | 367 | 320ms | 249ms | 762ms | 1.1s | 1.2s |
| ExpectationCalcWorker:PreWarm:expectation_calc_high | 225 | 1.4s | 1.4s | 2.1s | 2.5s | 2.6s |
| PgmqWorker:ProcessBatch:expectation_calc_high | 225 | 1.4s | 1.4s | 2.2s | 2.5s | 2.7s |
| PgmqWorker:DrainBuffer:expectation_calc_high | 200 | 389ms | 289ms | 1.1s | 1.5s | 1.6s |
| AdaptiveBatch:ProcessChunk:50 | 188 | 1.9s | 1.8s | 2.7s | 3.2s | 4.5s |
| Cache:Get | 119 | 602ms | 442ms | 982ms | 1.2s | 1.4s |
| PostgresL2Cache:Lookup | 118 | 581ms | 430ms | 979ms | 981ms | 982ms |
| PostgresL2Strategy:Get | 117 | 485ms | 343ms | 824ms | 830ms | 832ms |
| AlertService:Critical:외부 API 장애 | 94 | 377ms | 270ms | 938ms | 1.3s | 1.3s |
| Alert:SendCritical | 94 | 388ms | 277ms | 968ms | 1.3s | 1.3s |
| equipment:GetFromTiered | 74 | 286ms | 261ms | 429ms | 880ms | 880ms |
| Parser:StreamingParse:allPresets | 70 | 635ms | 674ms | 1.2s | 1.2s | 1.2s |
| ExpectationCalcWorker:BatchWrite:5 | 31 | 308ms | 241ms | 1.0s | 1.2s | 1.2s |
| EquipmentProvider:GetRawDataFanout | 29 | 573ms | 634ms | 855ms | 1.1s | 1.1s |
| ExpectationCalcWorker:BatchWrite:4 | 28 | 311ms | 251ms | 679ms | 979ms | 979ms |
| ExpectationCalcWorker:BatchWrite:6 | 28 | 354ms | 279ms | 975ms | 1.3s | 1.3s |
| NexonApiPgmqProcessor:PollAndProcess:nexon_retry_queue | 27 | 8.9s | 8.1s | 19.2s | 20.7s | 20.7s |
| ExpectationCalcWorker:BatchWrite:7 | 22 | 301ms | 247ms | 497ms | 1.1s | 1.1s |
| PostgresL2Strategy:Put | 21 | 608ms | 649ms | 940ms | 1.2s | 1.2s |
| PostgresL2Cache:Put | 21 | 633ms | 649ms | 1.1s | 1.2s | 1.2s |
| Cache:Put | 21 | 644ms | 649ms | 1.1s | 1.2s | 1.2s |
| Filter:MDC | 19 | 539ms | 611ms | 897ms | 897ms | 897ms |
| ExpectationCalcWorker:BatchWrite:3 | 19 | 420ms | 226ms | 1.5s | 1.5s | 1.5s |
| ExpectationCalcWorker:BatchWrite:2 | 19 | 285ms | 230ms | 829ms | 829ms | 829ms |
| ExpectationCalcWorker:BatchWrite:10 | 18 | 408ms | 334ms | 1.2s | 1.2s | 1.2s |
| ExpectationCalcWorker:BatchWrite:8 | 17 | 269ms | 255ms | 400ms | 400ms | 400ms |
| ExpectationCalcWorker:BatchWrite:9 | 10 | 366ms | 340ms | 869ms | 869ms | 869ms |
| PgmqClient:Send:nexon_retry_queue | 10 | 765ms | 684ms | 1.1s | 1.1s | 1.1s |
| ExpectationCalcWorker:BatchWrite:1 | 7 | 679ms | 538ms | 1.4s | 1.4s | 1.4s |
| CubeService:CalculateDP:INT_PERCENT | 6 | 667ms | 744ms | 876ms | 876ms | 876ms |
| CubeService:CalculateDP:LUK_PERCENT | 6 | 521ms | 647ms | 677ms | 677ms | 677ms |
| CubeService:CalculateDP:DEX_PERCENT | 5 | 383ms | 313ms | 740ms | 740ms | 740ms |
| CubeService:CalculateDP:STR_PERCENT | 5 | 672ms | 642ms | 832ms | 832ms | 832ms |
| CubeService:CalculateDP:LEVEL_DEX | 5 | 439ms | 379ms | 765ms | 765ms | 765ms |

---

## Analysis

### 1. CalculateOnly (54,661건, p95=28.6s) — dominant bottleneck

- 전체 slow entry의 **63.6%**를 차지
- p95=28.6s는 BulkheadFullException 타임아웃(30s) 근접
- 실제 계산은 빠르지만, Nexon API Bulkhead 대기로 인해 지연
- **Bulkhead 동시성을 50→250으로 올리면 p95가 크게 개선될 것** (Nexon rate limit 500/s 여유)

### 2. V4:PresetJoin (5,844건, avg=1.9s)

- 계산 로직 자체의 소요 시간
- Nexon API 호출 포함 (getCharacterBasic + getItemDataByOcid fan-out)

### 3. FanOutBatchLoader:Fetch (7,054건, avg=777ms)

- 장비 데이터 캐시 조회/API 호출
- avg=777ms는 API 호출 + 캐시 미스 포함

### 4. AdvisoryLock:ElectLeader (1,848건, avg=899ms)

- SingleFlight 리더 선출 대기
- 이미 리더가 계산 중일 때 팔로워가 폴링하는 시간

### 5. NexonApiPgmqProcessor:PollAndProcess:nexon_retry_queue (27건, avg=8.9s)

- 재시도 큐 처리 — 실패한 API 호출 재시도
- avg=8.9s는 재시도 대기 시간 포함
