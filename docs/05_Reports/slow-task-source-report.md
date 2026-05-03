# Slow Task Forensics Report

Log: `module-app/logs/app.log`
Total slow events: **92331**
Unique tasks: **15495**

## Top by Total Duration

| # | Task | Count | Avg | p50 | p95 | p99 | Max | Total (s) |
|---|------|-------|-----|-----|-----|-----|-----|-----------|
| 1 | `PgmqWorker:ProcessMessage:external_api_queue:MSG` | 39685 | 776ms | 698ms | 1306ms | 1774ms | 7294ms | 30807.4s |
| 2 | `ExternalApiWorker:Pipeline:*` | 18516 | 794ms | 707ms | 1291ms | 1784ms | 7253ms | 14696.5s |
| 3 | `PgmqWorker:ProcessMessage:expectation_calc_high:MSG` | 14733 | 265ms | 245ms | 391ms | 495ms | 987ms | 3909.8s |
| 4 | `TimeoutScanner:Scan:stale_jobs` | 54 | 43643ms | 1954ms | 238452ms | 261398ms | 281029ms | 2356.7s |
| 5 | `DlqReplayWorker:Replay` | 5 | 23807ms | 5150ms | 70070ms | 70070ms | 70070ms | 119.0s |
| 6 | `PgmqClient:Archive:expectation_calc_high:MSG` | 390 | 252ms | 232ms | 374ms | 489ms | 753ms | 98.1s |
| 7 | `PgmqWorker:ProcessBatch:external_api_queue` | 179 | 276ms | 239ms | 443ms | 668ms | 857ms | 49.4s |
| 8 | `PgmqWorker:ProcessBatch:expectation_calc_low` | 149 | 268ms | 254ms | 374ms | 494ms | 575ms | 39.9s |
| 9 | `PgmqWorker:ProcessBatch:expectation_calc_high` | 148 | 269ms | 244ms | 384ms | 639ms | 767ms | 39.9s |
| 10 | `GracefulShutdownHook:CoordinatorRun` | 5 | 6090ms | 5194ms | 14462ms | 14462ms | 14462ms | 30.4s |

## Top by p95

| # | Task | Count | Avg | p50 | p95 | p99 | Max | Total (s) |
|---|------|-------|-----|-----|-----|-----|-----|-----------|
| 1 | `TimeoutScanner:Scan:stale_jobs` | 54 | 43643ms | 1954ms | 238452ms | 261398ms | 281029ms | 2356.7s |
| 2 | `DlqReplayWorker:Replay` | 5 | 23807ms | 5150ms | 70070ms | 70070ms | 70070ms | 119.0s |
| 3 | `GracefulShutdownHook:CoordinatorRun` | 5 | 6090ms | 5194ms | 14462ms | 14462ms | 14462ms | 30.4s |
| 4 | `ShutdownCoordinator:Main` | 5 | 6077ms | 5168ms | 14458ms | 14458ms | 14458ms | 30.4s |
| 5 | `ShutdownCoordinator:ExecutePhase:ScheduledTaskLifecycleWrapper` | 1 | 9335ms | 9335ms | 9335ms | 9335ms | 9335ms | 9.3s |
| 6 | `ShutdownCoordinator:ExecutePhase:ThreadPoolTaskScheduler` | 5 | 4055ms | 4999ms | 4999ms | 4999ms | 4999ms | 20.3s |
| 7 | `CubeProbability:InitCsvLoad` | 5 | 2093ms | 2088ms | 2290ms | 2290ms | 2290ms | 10.5s |
| 8 | `ResultProjection:ProjectView:4782` | 1 | 1663ms | 1663ms | 1663ms | 1663ms | 1663ms | 1.7s |
| 9 | `ResultProjection:ProjectView:5172` | 1 | 1452ms | 1452ms | 1452ms | 1452ms | 1452ms | 1.5s |
| 10 | `ResultProjection:ProjectView:4802` | 1 | 1342ms | 1342ms | 1342ms | 1342ms | 1342ms | 1.3s |

## Top by Max

| # | Task | Count | Avg | p50 | p95 | p99 | Max | Total (s) |
|---|------|-------|-----|-----|-----|-----|-----|-----------|
| 1 | `TimeoutScanner:Scan:stale_jobs` | 54 | 43643ms | 1954ms | 238452ms | 261398ms | 281029ms | 2356.7s |
| 2 | `DlqReplayWorker:Replay` | 5 | 23807ms | 5150ms | 70070ms | 70070ms | 70070ms | 119.0s |
| 3 | `GracefulShutdownHook:CoordinatorRun` | 5 | 6090ms | 5194ms | 14462ms | 14462ms | 14462ms | 30.4s |
| 4 | `ShutdownCoordinator:Main` | 5 | 6077ms | 5168ms | 14458ms | 14458ms | 14458ms | 30.4s |
| 5 | `ShutdownCoordinator:ExecutePhase:ScheduledTaskLifecycleWrapper` | 1 | 9335ms | 9335ms | 9335ms | 9335ms | 9335ms | 9.3s |
| 6 | `PgmqWorker:ProcessMessage:external_api_queue:MSG` | 39685 | 776ms | 698ms | 1306ms | 1774ms | 7294ms | 30807.4s |
| 7 | `ExternalApiWorker:Pipeline:*` | 18516 | 794ms | 707ms | 1291ms | 1784ms | 7253ms | 14696.5s |
| 8 | `ShutdownCoordinator:ExecutePhase:ThreadPoolTaskScheduler` | 5 | 4055ms | 4999ms | 4999ms | 4999ms | 4999ms | 20.3s |
| 9 | `CubeProbability:InitCsvLoad` | 5 | 2093ms | 2088ms | 2290ms | 2290ms | 2290ms | 10.5s |
| 10 | `ResultProjection:ProjectView:4782` | 1 | 1663ms | 1663ms | 1663ms | 1663ms | 1663ms | 1.7s |

## Top by Count

| # | Task | Count | Avg | p50 | p95 | p99 | Max | Total (s) |
|---|------|-------|-----|-----|-----|-----|-----|-----------|
| 1 | `PgmqWorker:ProcessMessage:external_api_queue:MSG` | 39685 | 776ms | 698ms | 1306ms | 1774ms | 7294ms | 30807.4s |
| 2 | `ExternalApiWorker:Pipeline:*` | 18516 | 794ms | 707ms | 1291ms | 1784ms | 7253ms | 14696.5s |
| 3 | `PgmqWorker:ProcessMessage:expectation_calc_high:MSG` | 14733 | 265ms | 245ms | 391ms | 495ms | 987ms | 3909.8s |
| 4 | `PgmqClient:Archive:expectation_calc_high:MSG` | 390 | 252ms | 232ms | 374ms | 489ms | 753ms | 98.1s |
| 5 | `PgmqWorker:ProcessBatch:external_api_queue` | 179 | 276ms | 239ms | 443ms | 668ms | 857ms | 49.4s |
| 6 | `PgmqWorker:ProcessBatch:expectation_calc_low` | 149 | 268ms | 254ms | 374ms | 494ms | 575ms | 39.9s |
| 7 | `PgmqWorker:ProcessBatch:expectation_calc_high` | 148 | 269ms | 244ms | 384ms | 639ms | 767ms | 39.9s |
| 8 | `PgmqClient:Archive:external_api_queue:MSG` | 60 | 252ms | 239ms | 318ms | 459ms | 606ms | 15.1s |
| 9 | `PgmqClient:Read:external_api_queue` | 56 | 283ms | 245ms | 507ms | 586ms | 777ms | 15.8s |
| 10 | `TimeoutScanner:Scan:stale_jobs` | 54 | 43643ms | 1954ms | 238452ms | 261398ms | 281029ms | 2356.7s |

## Time Window Density (30s buckets)

| Time | Slow Count | Avg ms | Top Task | Correlated Signals |
|------|-----------|--------|----------|-------------------|
| 03:14:00 | 1483 | 553ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(587) | expectation_calc_high=4031, external_api_queue=1879, TimeoutScanner=204, OCID_RESOLVE_TIMEOUT=203, AdvisoryLock=201 |
| 06:25:00 | 1451 | 575ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(507) | expectation_calc_high=4523, external_api_queue=1677, DlqReplayWorker=291, AdvisoryLock=144, Pipeline completed=121 |
| 03:18:00 | 1394 | 518ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(553) | expectation_calc_high=4031, external_api_queue=1333, OCID_RESOLVE_TIMEOUT=369, TimeoutScanner=369, AdvisoryLock=134 |
| 03:13:30 | 1391 | 560ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(549) | expectation_calc_high=4146, external_api_queue=1880, AdvisoryLock=197, Pipeline completed=151, TimeoutScanner=19 |
| 03:15:00 | 1377 | 521ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(554) | expectation_calc_high=4007, external_api_queue=1318, OCID_RESOLVE_TIMEOUT=353, TimeoutScanner=353, AdvisoryLock=125 |
| 06:24:30 | 1366 | 589ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(461) | expectation_calc_high=4331, external_api_queue=1676, DlqReplayWorker=327, AdvisoryLock=166, Pipeline completed=120 |
| 04:35:00 | 1351 | 529ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(483) | expectation_calc_high=4252, external_api_queue=1383, OCID_RESOLVE_TIMEOUT=375, TimeoutScanner=375, AdvisoryLock=108 |
| 04:33:00 | 1240 | 591ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(367) | expectation_calc_high=4499, external_api_queue=1916, AdvisoryLock=166, Pipeline completed=143, PgmqClient:Archive=9 |
| 06:25:30 | 1221 | 629ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(409) | expectation_calc_high=4769, external_api_queue=1725, AdvisoryLock=148, Pipeline completed=132, DlqReplayWorker=52 |
| 04:34:30 | 1211 | 550ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(395) | expectation_calc_high=4582, external_api_queue=1573, OCID_RESOLVE_TIMEOUT=150, TimeoutScanner=150, AdvisoryLock=129 |
| 03:14:30 | 1199 | 516ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(446) | expectation_calc_high=4391, external_api_queue=1508, OCID_RESOLVE_TIMEOUT=183, TimeoutScanner=183, AdvisoryLock=148 |
| 04:34:00 | 1192 | 588ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(393) | expectation_calc_high=4241, external_api_queue=1609, TimeoutScanner=279, OCID_RESOLVE_TIMEOUT=277, AdvisoryLock=137 |
| 03:16:00 | 1183 | 582ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(474) | expectation_calc_high=4289, external_api_queue=1324, TimeoutScanner=378, OCID_RESOLVE_TIMEOUT=377, AdvisoryLock=132 |
| 04:36:00 | 1159 | 497ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(416) | expectation_calc_high=4504, external_api_queue=1197, OCID_RESOLVE_TIMEOUT=426, TimeoutScanner=426, AdvisoryLock=102 |
| 04:37:00 | 1158 | 532ms | PgmqWorker:ProcessMessage:expectation_calc_high:MSG(427) | expectation_calc_high=4389, external_api_queue=1173, TimeoutScanner=422, OCID_RESOLVE_TIMEOUT=281, AdvisoryLock=103 |

## Top Task Details

Each section: stats → representative slow log → nearby logs → source code → suspected cause.

### 1. `PgmqWorker:ProcessMessage:external_api_queue:MSG`

**Stats:** count=39685, avg=776ms, p50=698ms, p95=1306ms, p99=1774ms, max=7294ms, total=30807.4s

**Max slow event** (7294ms):

```
2026-04-30 03:13:23.524 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:29991 (7294ms)
```

**Nearby logs** (line 251282~251322):

```
    2026-04-30 03:13:23.476 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:51486 (202ms)
    2026-04-30 03:13:23.476 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=b5cefc23-5381-4199-a1b6-2a6ba984737b] Calculation completed with result saved
    2026-04-30 03:13:23.476 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=프하하, taskId=51493
    2026-04-30 03:13:23.483 [external_api_queue-worker] [] INFO  m.e.i.worker.ExternalApiWorker : [jobId=b5cefc23-5381-4199-a1b6-2a6ba984737b] Pipeline completed
    2026-04-30 03:13:23.484 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExternalApiWorker:Pipeline:발톱 (7253ms)
    2026-04-30 03:13:23.484 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:29991 (7254ms)
    2026-04-30 03:13:23.484 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:51481 (572ms)
    2026-04-30 03:13:23.484 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=패트, taskId=51497
    2026-04-30 03:13:23.493 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=eed9bfee-1893-4d43-8052-364edd0792c5] Dispatched to consolidated external API pipeline
    2026-04-30 03:13:23.495 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=0f4c7e54-608e-4b8c-b6f2-20d41c9c5d15] Dispatched to consolidated external API pipeline
    2026-04-30 03:13:23.499 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=eed9bfee-1893-4d43-8052-364edd0792c5
    2026-04-30 03:13:23.501 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=0f4c7e54-608e-4b8c-b6f2-20d41c9c5d15
    2026-04-30 03:13:23.517 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=날아라둥둥띠, taskId=51500
    2026-04-30 03:13:23.521 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=44f6c812-2db1-4b19-89c7-ebe147f60e89] Job created in REQUESTED state
>>> 2026-04-30 03:13:23.524 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:29991 (7294ms)
    2026-04-30 03:13:23.525 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=83a54ad9-2428-4fae-953e-939aab00aee3] Dispatched to consolidated external API pipeline
    2026-04-30 03:13:23.530 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=83a54ad9-2428-4fae-953e-939aab00aee3
    2026-04-30 03:13:23.535 [external_api_queue-worker] [] INFO  m.e.i.l.PostgresAdvisoryLockStrategy : 👑 [Leader] Acquired xact lock for key: badaa202e208c7008ea23a0eff2442d4efe8d04e6d233bd35cf2fabdeb93fb0d
    2026-04-30 03:13:23.535 [external_api_queue-worker] [] INFO  m.e.i.a.aspect.NexonDataCacheAspect : [Leader] 캐시 갱신 시작: badaa202e208c7008ea23a0eff2442d4efe8d04e6d233bd35cf2fabdeb93fb0d
    2026-04-30 03:13:23.537 [external_api_queue-worker] [] INFO  m.e.i.e.impl.RealNexonApiClient : [NexonApi] Equipment data request (Cache Miss): ocid=badaa202e208c7008ea23a0eff2442d4efe8d04e6d233bd35cf2fabdeb93fb0d
    2026-04-30 03:13:23.539 [external_api_queue-worker] [] INFO  m.e.i.e.impl.RealNexonApiClient : [NexonApi] OCID lookup: characterName=데카짱2
    2026-04-30 03:13:23.542 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=e212e1d9-ee9a-46d5-b818-79bb8443feb0] Calculation completed with result saved
    2026-04-30 03:13:23.544 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:51489 (239ms)
    2026-04-30 03:13:23.544 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=구희, taskId=51494
    2026-04-30 03:13:23.545 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=제이슨상하차, taskId=51495
    2026-04-30 03:13:23.550 [external_api_queue-worker] [] INFO  m.e.i.worker.ExternalApiWorker : [jobId=e212e1d9-ee9a-46d5-b818-79bb8443feb0] Pipeline completed
    2026-04-30 03:13:23.550 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExternalApiWorker:Pipeline:알티 (925ms)
    2026-04-30 03:13:23.550 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:30002 (925ms)
    2026-04-30 03:13:23.557 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=521e3dae-c7b4-4234-b68d-53c0c5c49a12] Job created in REQUESTED state
    2026-04-30 03:13:23.575 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=97575084-78ff-4412-b271-78e1f85f7d9f] Job created in REQUESTED state
    2026-04-30 03:13:23.583 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=b04a94c6-ecb4-4d8b-ab34-ee538889a246] Job created in REQUESTED state
    2026-04-30 03:13:23.584 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=50e30604-91b1-4165-8133-2dd05293310b] Calculation started by ExternalApiWorker
```

**Source:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt` — `processSingleMessage()`

```kotlin
   415 |      *   <li>처리 실패 + 재시도 불가 -> delete (DLQ)
   416 |      * </ul>
   417 |      */
   418 |     private fun processSingleMessage(message: PgmqMessage<T>): Boolean {
   419 |         val maxRetries = workerSettings.maxRetries ?: config.common.maxRetries
   420 |         val context = TaskContext.of("PgmqWorker", "ProcessMessage", "$queueName:${message.messageId}")
   421 | 
   422 |         // 재시도 메시지 추적
   423 |         if (message.readCount > 1) {
   424 |             metrics.retry.increment()
   425 |         }
   426 | 
   427 |         return executor.executeWithFinally(
   428 |             task = {
   429 |                 metrics.concurrentIncrement()
   430 |                 val success = executor.executeOrDefault(
   431 |                     { process(message) },
   432 |                     false,
   433 |                     context,
   434 |                 )
   435 | 
   436 |                 when {
   437 |                     success -> {
   438 |                         pgmqClient.archive(queueName, message.messageId)
   439 |                         metrics.success.increment()
   440 |                         log.debug("[{}] Archived message: msgId={}", queueName, message.messageId)
   441 |                     }
   442 |                     message.isRetryable(maxRetries) -> {
   443 |                         onProcessingFailed(message)
   444 |                         metrics.failure.increment()
   445 |                         log.warn("[{}] Message will be retried: msgId={}, readCount={}", queueName, message.messageId, message.readCount)
   446 |                     }
   447 |                     else -> {
   448 |                         pgmqClient.archive(queueName, message.messageId)
   449 |                         metrics.failure.increment()
   450 |                         metrics.dlq.increment()

```

**Suspected cause:**

- Calls subclass `process()` → full pipeline (API + DB writes)
- After pipeline: `pgmqClient.archive()` adds another DB roundtrip
- Total per message: API (~500ms) + DB writes (~250ms) + archive (~250ms)

**Correlated events:**
- Lock contention in same window

### 2. `ExternalApiWorker:Pipeline:*`

**Stats:** count=18516, avg=794ms, p50=707ms, p95=1291ms, p99=1784ms, max=7253ms, total=14696.5s

**Max slow event** (7253ms):

```
2026-04-30 03:13:23.484 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExternalApiWorker:Pipeline:발톱 (7253ms)
```

**Nearby logs** (line 251266~251306):

```
    2026-04-30 03:13:23.445 [external_api_queue-worker] [] INFO  m.e.i.c.t.AbstractTieredCacheService : [Cache] MISS | cache=equipment | key=badaa202e208c7008ea23a0eff2442d4efe8d04e6d233bd35cf2fabdeb93fb0d
    2026-04-30 03:13:23.453 [scheduler-3] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessBatch:expectation_calc_high (357ms)
    2026-04-30 03:13:23.456 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=발락, taskId=51498
    2026-04-30 03:13:23.467 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=52dd4bfe-dd2c-4f4b-944c-43e60b10391b] Dispatched to consolidated external API pipeline
    2026-04-30 03:13:23.469 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=83a54ad9-2428-4fae-953e-939aab00aee3] Job created in REQUESTED state
    2026-04-30 03:13:23.475 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=52dd4bfe-dd2c-4f4b-944c-43e60b10391b
    2026-04-30 03:13:23.476 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:51486 (202ms)
    2026-04-30 03:13:23.476 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=b5cefc23-5381-4199-a1b6-2a6ba984737b] Calculation completed with result saved
    2026-04-30 03:13:23.476 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=프하하, taskId=51493
    2026-04-30 03:13:23.483 [external_api_queue-worker] [] INFO  m.e.i.worker.ExternalApiWorker : [jobId=b5cefc23-5381-4199-a1b6-2a6ba984737b] Pipeline completed
>>> 2026-04-30 03:13:23.484 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExternalApiWorker:Pipeline:발톱 (7253ms)
    2026-04-30 03:13:23.484 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:29991 (7254ms)
    2026-04-30 03:13:23.484 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:51481 (572ms)
    2026-04-30 03:13:23.484 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=패트, taskId=51497
    2026-04-30 03:13:23.493 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=eed9bfee-1893-4d43-8052-364edd0792c5] Dispatched to consolidated external API pipeline
    2026-04-30 03:13:23.495 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=0f4c7e54-608e-4b8c-b6f2-20d41c9c5d15] Dispatched to consolidated external API pipeline
    2026-04-30 03:13:23.499 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=eed9bfee-1893-4d43-8052-364edd0792c5
    2026-04-30 03:13:23.501 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=0f4c7e54-608e-4b8c-b6f2-20d41c9c5d15
    2026-04-30 03:13:23.517 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=날아라둥둥띠, taskId=51500
    2026-04-30 03:13:23.521 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=44f6c812-2db1-4b19-89c7-ebe147f60e89] Job created in REQUESTED state
    2026-04-30 03:13:23.524 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:29991 (7294ms)
    2026-04-30 03:13:23.525 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=83a54ad9-2428-4fae-953e-939aab00aee3] Dispatched to consolidated external API pipeline
    2026-04-30 03:13:23.530 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=83a54ad9-2428-4fae-953e-939aab00aee3
    2026-04-30 03:13:23.535 [external_api_queue-worker] [] INFO  m.e.i.l.PostgresAdvisoryLockStrategy : 👑 [Leader] Acquired xact lock for key: badaa202e208c7008ea23a0eff2442d4efe8d04e6d233bd35cf2fabdeb93fb0d
```

**Source:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt` — `processPipeline()`

```kotlin
   100 |         }
   101 |     }
   102 | 
   103 |     private fun processPipeline(payload: ExternalApiJobPayload) {
   104 |         val jobId = UUID.fromString(payload.jobId)
   105 | 
   106 |         // Early exit: skip expensive API calls if job already completed/processing
   107 |         val existingJob = jobPort.findJobById(jobId)
   108 |         if (existingJob != null && existingJob.status != CalculationJobStatus.OCID_RESOLVING && existingJob.status != CalculationJobStatus.REQUESTED) {
   109 |             log.debug("[jobId={}] Skipping — already in state {}", jobId, existingJob.status)
   110 |             return
   111 |         }
   112 | 
   113 |         // Step 1: Resolve OCID (Nexon API ~200ms)
   114 |         val ocid = resolveOcid(jobId, payload.userIgn)
   115 | 
   116 |         // Step 2: Fetch equipment data (Nexon API ~300ms)
   117 |         val equipmentResponse = equipmentFetchProvider.fetchWithCache(ocid)
   118 |         val snapshotData = objectMapper.writeValueAsBytes(equipmentResponse)
   119 | 
   120 |         // Step 3: Save snapshot + CalculationInput
   121 |         val objectKey = generateObjectKey(jobId)
   122 |         val snapshotId = UUID.randomUUID()
   123 |         val snapshot = CalculationSnapshot(
   124 |             snapshotId = snapshotId,
   125 |             jobId = jobId,
   126 |             objectKey = objectKey,
   127 |             storageType = "LOCAL",
   128 |             characterId = ocid,
   129 |             presetNo = payload.presetNo,
   130 |             expiresAt = Instant.now().plusSeconds(86400),
   131 |         )
   132 |         val putResult = snapshotStore.put(snapshot, snapshotData)
   133 | 
   134 |         val inputItems = (equipmentResponse.itemEquipment ?: emptyList()).map { item ->
   135 |             val itemMap = objectMapper.convertValue(item, Map::class.java) as Map<*, *>

```

**Suspected cause:**

- Nexon API calls: OCID (~200ms) + Equipment (~300ms) = ~500ms
- Snapshot save + CalculationInput save + completeCalculationWithResult = 3 DB writes
- Inline view projection adds 1 more DB write (upsertFromCalculation)
- Pipeline total: ~500ms API + ~500ms DB = ~1000ms per job

**Correlated events:**
- Lock contention in same window

### 3. `PgmqWorker:ProcessMessage:expectation_calc_high:MSG`

**Stats:** count=14733, avg=265ms, p50=245ms, p95=391ms, p99=495ms, max=987ms, total=3909.8s

**Max slow event** (987ms):

```
2026-04-30 03:15:02.484 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54342 (987ms)
```

**Nearby logs** (line 288395~288435):

```
    2026-04-30 03:15:02.388 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=13e0efb4-18cf-4d17-972e-0f9ab42928fb] Job created in REQUESTED state
    2026-04-30 03:15:02.441 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=e6669fd4-1cdc-405e-bb95-3232313a7a60] Dispatched to consolidated external API pipeline
    2026-04-30 03:15:02.470 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=e6669fd4-1cdc-405e-bb95-3232313a7a60
    2026-04-30 03:15:02.470 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExpectationCalcWorker:Process:미삼민 (379ms)
    2026-04-30 03:15:02.470 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54358 (379ms)
    2026-04-30 03:15:02.473 [external_api_queue-worker] [] WARN  m.e.i.p.EquipmentFetchProvider : [EquipmentProvider] Slow API fetch: ocid=139040bd652455a7a7baa3cf20a9d50d took 728ms
    2026-04-30 03:15:02.476 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: AdvisoryLock:ElectLeader:139040bd652455a7a7baa3cf20a9d50d (732ms)
    2026-04-30 03:15:02.484 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqClient:Archive:expectation_calc_high:54342 (753ms)
>>> 2026-04-30 03:15:02.484 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54342 (987ms)
    2026-04-30 03:15:02.484 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=13e0efb4-18cf-4d17-972e-0f9ab42928fb] Dispatched to consolidated external API pipeline
    2026-04-30 03:15:02.485 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=헤리, taskId=54364
    2026-04-30 03:15:02.496 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=13e0efb4-18cf-4d17-972e-0f9ab42928fb
    2026-04-30 03:15:02.497 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExpectationCalcWorker:Process:유츠메 (719ms)
    2026-04-30 03:15:02.497 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54355 (719ms)
    2026-04-30 03:15:02.523 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=6a15b553-769b-4177-903f-db13057f9ed5] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 03:15:02.523 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54355 (745ms)
    2026-04-30 03:15:02.523 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=뀨삐약, taskId=54365
    2026-04-30 03:15:02.589 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=6a15b553-769b-4177-903f-db13057f9ed5] Timeout detected: OCID_RESOLVING stale for >30s
    2026-04-30 03:15:02.589 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=081d6326-fe83-48d8-b2a7-1f4028177586] Dispatched to consolidated external API pipeline
    2026-04-30 03:15:02.600 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=081d6326-fe83-48d8-b2a7-1f4028177586
    2026-04-30 03:15:02.600 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqClient:Archive:expectation_calc_high:54366 (489ms)
    2026-04-30 03:15:02.601 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExpectationCalcWorker:Process:막좡 (798ms)
    2026-04-30 03:15:02.601 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54360 (798ms)
    2026-04-30 03:15:02.602 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54366 (679ms)
    2026-04-30 03:15:02.602 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=개곰탱, taskId=54356
    2026-04-30 03:15:02.614 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=10fef7dd-a3f0-48f6-9643-a1f840e8a5e6] Calculation completed with result saved
    2026-04-30 03:15:02.618 [scheduler-3] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqClient:Read:external_api_queue (586ms)
```

**Source:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt` — `processSingleMessage()`

```kotlin
   415 |      *   <li>처리 실패 + 재시도 불가 -> delete (DLQ)
   416 |      * </ul>
   417 |      */
   418 |     private fun processSingleMessage(message: PgmqMessage<T>): Boolean {
   419 |         val maxRetries = workerSettings.maxRetries ?: config.common.maxRetries
   420 |         val context = TaskContext.of("PgmqWorker", "ProcessMessage", "$queueName:${message.messageId}")
   421 | 
   422 |         // 재시도 메시지 추적
   423 |         if (message.readCount > 1) {
   424 |             metrics.retry.increment()
   425 |         }
   426 | 
   427 |         return executor.executeWithFinally(
   428 |             task = {
   429 |                 metrics.concurrentIncrement()
   430 |                 val success = executor.executeOrDefault(
   431 |                     { process(message) },
   432 |                     false,
   433 |                     context,
   434 |                 )
   435 | 
   436 |                 when {
   437 |                     success -> {
   438 |                         pgmqClient.archive(queueName, message.messageId)
   439 |                         metrics.success.increment()
   440 |                         log.debug("[{}] Archived message: msgId={}", queueName, message.messageId)
   441 |                     }
   442 |                     message.isRetryable(maxRetries) -> {
   443 |                         onProcessingFailed(message)
   444 |                         metrics.failure.increment()
   445 |                         log.warn("[{}] Message will be retried: msgId={}, readCount={}", queueName, message.messageId, message.readCount)
   446 |                     }
   447 |                     else -> {
   448 |                         pgmqClient.archive(queueName, message.messageId)
   449 |                         metrics.failure.increment()
   450 |                         metrics.dlq.increment()

```

**Suspected cause:**

- Calls subclass `process()` → full pipeline (API + DB writes)
- After pipeline: `pgmqClient.archive()` adds another DB roundtrip
- Total per message: API (~500ms) + DB writes (~250ms) + archive (~250ms)

**Correlated events:**
- TimeoutScanner active in same window
- Lock contention in same window

### 4. `TimeoutScanner:Scan:stale_jobs`

**Stats:** count=54, avg=43643ms, p50=1954ms, p95=238452ms, p99=261398ms, max=281029ms, total=2356.7s

**Max slow event** (281029ms):

```
2026-04-30 04:42:52.966 [scheduler-1] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: TimeoutScanner:Scan:stale_jobs (281029ms)
```

**Nearby logs** (line 843137~843177):

```
    2026-04-30 04:42:52.786 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=83f00a93-4651-4fea-bb81-c509a5bfa6e0] Calculation started by ExternalApiWorker
    2026-04-30 04:42:52.803 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=b71a3e72-5e1c-417a-8ab3-d45540a75ec2] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 04:42:52.807 [external_api_queue-worker] [] INFO  m.e.i.c.t.AbstractTieredCacheService : [Cache] MISS | cache=equipment | key=357dcc238a390729e767e5c72d10a254
    2026-04-30 04:42:52.808 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=b71a3e72-5e1c-417a-8ab3-d45540a75ec2] Timeout detected: OCID_RESOLVING stale for >30s
    2026-04-30 04:42:52.816 [external_api_queue-worker] [] INFO  m.e.i.l.PostgresAdvisoryLockStrategy : 👑 [Leader] Acquired xact lock for key: 357dcc238a390729e767e5c72d10a254
    2026-04-30 04:42:52.816 [external_api_queue-worker] [] INFO  m.e.i.a.aspect.NexonDataCacheAspect : [Leader] 캐시 갱신 시작: 357dcc238a390729e767e5c72d10a254
    2026-04-30 04:42:52.817 [external_api_queue-worker] [] INFO  m.e.i.e.impl.RealNexonApiClient : [NexonApi] Equipment data request (Cache Miss): ocid=357dcc238a390729e767e5c72d10a254
    2026-04-30 04:42:52.824 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=4df06354-54bb-410c-9822-2a6e63c7e577] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 04:42:52.826 [external_api_queue-worker] [] WARN  m.e.i.p.EquipmentFetchProvider : [EquipmentProvider] Slow API fetch: ocid=05c2c7c3b3200402f6924c0a6715e60defe8d04e6d233bd35cf2fabdeb93fb0d took 249ms
    2026-04-30 04:42:52.827 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: AdvisoryLock:ElectLeader:05c2c7c3b3200402f6924c0a6715e60defe8d04e6d233bd35cf2fabdeb93fb0d (251ms)
    2026-04-30 04:42:52.830 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=4df06354-54bb-410c-9822-2a6e63c7e577] Timeout detected: OCID_RESOLVING stale for >30s
    2026-04-30 04:42:52.871 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=83f00a93-4651-4fea-bb81-c509a5bfa6e0] Calculation completed with result saved
    2026-04-30 04:42:52.897 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=fa353a3c-6dff-498f-a2e8-8ca3a402d211] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 04:42:52.904 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=fa353a3c-6dff-498f-a2e8-8ca3a402d211] Timeout detected: OCID_RESOLVING stale for >30s
    2026-04-30 04:42:52.919 [external_api_queue-worker] [] WARN  m.e.i.p.EquipmentFetchProvider : [EquipmentProvider] Slow API fetch: ocid=1cbd5713081128f8d1acc8ed3f8b6d4befe8d04e6d233bd35cf2fabdeb93fb0d took 154ms
    2026-04-30 04:42:52.938 [external_api_queue-worker] [] WARN  m.e.i.p.EquipmentFetchProvider : [EquipmentProvider] Slow API fetch: ocid=b743eb1c15eac5b61248dff9adcf168a took 171ms
    2026-04-30 04:42:52.941 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=379b2aea-3733-4e7b-b5a2-822b85b141c4] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 04:42:52.948 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=379b2aea-3733-4e7b-b5a2-822b85b141c4] Timeout detected: OCID_RESOLVING stale for >30s
>>> 2026-04-30 04:42:52.966 [scheduler-1] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: TimeoutScanner:Scan:stale_jobs (281029ms)
    2026-04-30 04:42:52.994 [external_api_queue-worker] [] WARN  m.e.i.p.EquipmentFetchProvider : [EquipmentProvider] Slow API fetch: ocid=357dcc238a390729e767e5c72d10a254 took 177ms
    2026-04-30 04:42:53.010 [external_api_queue-worker] [] INFO  m.e.i.worker.ExternalApiWorker : [jobId=83f00a93-4651-4fea-bb81-c509a5bfa6e0] Pipeline completed
    2026-04-30 04:42:53.010 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExternalApiWorker:Pipeline:타락파워승정 (531ms)
    2026-04-30 04:42:53.010 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:43450 (531ms)
    2026-04-30 04:42:53.028 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=5c2652fd-016d-4c77-baf2-4f67cd01995d] Calculation started by ExternalApiWorker
    2026-04-30 04:42:53.033 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:43450 (554ms)
    2026-04-30 04:42:53.047 [external_api_queue-worker] [] INFO  m.e.i.e.impl.RealNexonApiClient : [NexonApi] OCID lookup: characterName=이달영
```

**Source:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobTimeoutScanner.kt` — `scanStaleJobs()`

```kotlin
    19 |     private val log = LoggerFactory.getLogger(javaClass)
    20 | 
    21 |     @Scheduled(fixedDelayString = "\${job.scanner.timeout-interval-ms:30000}")
    22 |     fun scanStaleJobs() {
    23 |         val context = TaskContext.of("TimeoutScanner", "Scan", "stale_jobs")
    24 | 
    25 |         executor.executeVoid({
    26 |             val staleOcidResolving = jobPort.findStaleJobs(CalculationJobStatus.OCID_RESOLVING, 120)
    27 |                 .take(maxBatchSize)
    28 |             for (job in staleOcidResolving) {
    29 |                 val current = jobPort.findJobById(job.jobId)
    30 |                 if (current != null && current.status == CalculationJobStatus.OCID_RESOLVING) {
    31 |                     jobService.retryExternalApiJob(job.jobId)
    32 |                     log.warn("[jobId={}] Timeout: OCID_RESOLVING stale >120s, retried via consolidated queue", job.jobId)
    33 |                 }
    34 |             }
    35 | 
    36 |             val staleApiRequested = jobPort.findStaleJobs(CalculationJobStatus.API_REQUESTED, 300)
    37 |                 .take(maxBatchSize)
    38 |             for (job in staleApiRequested) {
    39 |                 val current = jobPort.findJobById(job.jobId)
    40 |                 if (current != null && current.status == CalculationJobStatus.API_REQUESTED) {
    41 |                     jobService.retryExternalApiJob(job.jobId)
    42 |                     log.warn("[jobId={}] Timeout: API_REQUESTED stale >300s, retried via consolidated queue", job.jobId)
    43 |                 }
    44 |             }
    45 | 
    46 |             val staleRetrying = jobPort.findStaleJobs(CalculationJobStatus.RETRYING, 180)
    47 |                 .take(maxBatchSize)
    48 |             for (job in staleRetrying) {
    49 |                 val current = jobPort.findJobById(job.jobId)
    50 |                 if (current != null && current.status == CalculationJobStatus.RETRYING) {
    51 |                     jobService.retryExternalApiJob(job.jobId)
    52 |                     log.warn("[jobId={}] Timeout: RETRYING stale >180s, retried via consolidated queue", job.jobId)
    53 |                 }
    54 |             }

```

**Suspected cause:**

- Scanner dispatches retries without CAS protection → duplicate messages flood queue
- Stale threshold too short for queue backlog wait times
- Uses legacy topic dispatch instead of consolidated queue

**Correlated events:**
- Lock contention in same window

### 5. `DlqReplayWorker:Replay`

**Stats:** count=5, avg=23807ms, p50=5150ms, p95=70070ms, p99=70070ms, max=70070ms, total=119.0s

**Max slow event** (70070ms):

```
2026-04-30 06:25:35.503 [scheduler-2] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: DlqReplayWorker:Replay (70070ms)
```

**Nearby logs** (line 1022777~1022817):

```
    2026-04-30 06:25:35.438 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=5d3dd6e4-16e9-4947-9d92-c03eec0affd7] Dispatched to consolidated external API pipeline
    2026-04-30 06:25:35.439 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=65442a9b-c8d4-4343-a169-372f531dd225] Dispatched to consolidated external API pipeline
    2026-04-30 06:25:35.445 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=5d3dd6e4-16e9-4947-9d92-c03eec0affd7
    2026-04-30 06:25:35.446 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=115160e1-fbc5-49db-9a75-7ee113a3287c] Job created in REQUESTED state
    2026-04-30 06:25:35.446 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=65442a9b-c8d4-4343-a169-372f531dd225
    2026-04-30 06:25:35.454 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=쁨루트, taskId=73234
    2026-04-30 06:25:35.463 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=0b3cabc2-860f-47b2-b350-9667b0c8a976] Job created in REQUESTED state
    2026-04-30 06:25:35.470 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=2d17ef01-5d1d-44fe-87d6-63d48293a7b4] Job created in REQUESTED state
    2026-04-30 06:25:35.475 [expectation_calc_high-worker] [] WARN  m.e.i.job.CalculationJobService : [jobId=0b3cabc2-860f-47b2-b350-9667b0c8a976] Cannot transition to OCID_RESOLVING
    2026-04-30 06:25:35.482 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=0b3cabc2-860f-47b2-b350-9667b0c8a976
    2026-04-30 06:25:35.490 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=근육파괴원중, taskId=73238
    2026-04-30 06:25:35.495 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=4c908600-7251-4b0a-9ab1-f6bedb466701] Job created in REQUESTED state
    2026-04-30 06:25:35.495 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=c4a5d021-397e-4eef-a08c-6c7621b64492] Job created in REQUESTED state
    2026-04-30 06:25:35.497 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=634d6a31-7533-40ac-b842-5fcb247ca204] Calculation started by ExternalApiWorker
    2026-04-30 06:25:35.503 [scheduler-2] [] INFO  m.e.i.pgmq.DlqReplayWorker : [DlqReplayWorker] Summary: replayed=749, permanentFailures=0
>>> 2026-04-30 06:25:35.503 [scheduler-2] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: DlqReplayWorker:Replay (70070ms)
    2026-04-30 06:25:35.505 [expectation_calc_high-worker] [] WARN  m.e.i.job.CalculationJobService : [jobId=c4a5d021-397e-4eef-a08c-6c7621b64492] Cannot transition to OCID_RESOLVING
    2026-04-30 06:25:35.505 [expectation_calc_high-worker] [] WARN  m.e.i.job.CalculationJobService : [jobId=4c908600-7251-4b0a-9ab1-f6bedb466701] Cannot transition to OCID_RESOLVING
    2026-04-30 06:25:35.510 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=c4a5d021-397e-4eef-a08c-6c7621b64492
    2026-04-30 06:25:35.511 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=4c908600-7251-4b0a-9ab1-f6bedb466701
    2026-04-30 06:25:35.510 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:73677 (219ms)
    2026-04-30 06:25:35.511 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=로랑, taskId=73242
    2026-04-30 06:25:35.512 [expectation_calc_high-worker] [] WARN  m.e.i.job.CalculationJobService : [jobId=2d17ef01-5d1d-44fe-87d6-63d48293a7b4] Cannot transition to OCID_RESOLVING
    2026-04-30 06:25:35.515 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=째툇, taskId=73249
    2026-04-30 06:25:35.516 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=검첩, taskId=73691
    2026-04-30 06:25:35.517 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=2d17ef01-5d1d-44fe-87d6-63d48293a7b4
    2026-04-30 06:25:35.524 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=여삐, taskId=73692
    2026-04-30 06:25:35.530 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=5264d559-af1a-46a1-b96f-efe6e4b3453b] Job created in REQUESTED state
    2026-04-30 06:25:35.554 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=5264d559-af1a-46a1-b96f-efe6e4b3453b] Dispatched to consolidated external API pipeline
    2026-04-30 06:25:35.555 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=b19ae260-f61c-4676-ad66-dda5c928ccc7] Job created in REQUESTED state
    2026-04-30 06:25:35.560 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=5264d559-af1a-46a1-b96f-efe6e4b3453b
    2026-04-30 06:25:35.566 [expectation_calc_high-worker] [] WARN  m.e.i.job.CalculationJobService : [jobId=b19ae260-f61c-4676-ad66-dda5c928ccc7] Cannot transition to OCID_RESOLVING
    2026-04-30 06:25:35.570 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=9ba945ab-0a57-4ac3-a37f-36a0ef55be01] Job created in REQUESTED state
    2026-04-30 06:25:35.572 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=b19ae260-f61c-4676-ad66-dda5c928ccc7
    2026-04-30 06:25:35.583 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=115160e1-fbc5-49db-9a75-7ee113a3287c] Dispatched to consolidated external API pipeline
```

**Source:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/DlqReplayWorker.kt` — `replayDeadLetters()`

```kotlin
    57 |     }
    58 | 
    59 |     @Scheduled(fixedDelayString = "\${pgmq.dlq.replay-interval-ms:3600000}")
    60 |     fun replayDeadLetters() {
    61 |         if (!lifecycleWrapper.beforeTask()) return
    62 |         val context = TaskContext.of("DlqReplayWorker", "Replay")
    63 | 
    64 |         executor.executeWithFinally(
    65 |             task = { doReplay() },
    66 |             finallyBlock = { lifecycleWrapper.afterTask() },
    67 |             context = context,
    68 |         )
    69 |     }
    70 | 
    71 |     private fun doReplay() {
    72 |         var totalReplayed = 0
    73 |         var totalPermanent = 0
    74 | 
    75 |         for (queueName in QUEUE_NAMES) {
    76 |             discoverAndTrack(queueName)
    77 |             totalReplayed += replayEligible(queueName)
    78 |             totalPermanent += alertPermanentFailures(queueName)
    79 |         }
    80 | 
    81 |         if (totalReplayed > 0 || totalPermanent > 0) {
    82 |             log.info("[DlqReplayWorker] Summary: replayed={}, permanentFailures={}", totalReplayed, totalPermanent)
    83 |         }
    84 |     }
    85 | 
    86 |     /**
    87 |      * PGMQ archive 테이블에서 DLQ 메시지(read_ct > 1) 중 추적되지 않은 것을 발견하여 등록
    88 |      *
    89 |      * <p>read_ct > 1 필터로 성공적으로 처리된 메시지(read_ct = 1)를 제외.
    90 |      * 오직 재시도 끝에 실패한 메시지만 추적 대상.
    91 |      */
    92 |     private fun discoverAndTrack(queueName: String) {

```

**Suspected cause:**

- Scans 6 queue archive tables sequentially
- Per-queue: discover + track + replay = multiple DB roundtrips
- No parallelization across queues

### 6. `PgmqClient:Archive:expectation_calc_high:MSG`

**Stats:** count=390, avg=252ms, p50=232ms, p95=374ms, p99=489ms, max=753ms, total=98.1s

**Max slow event** (753ms):

```
2026-04-30 03:15:02.484 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqClient:Archive:expectation_calc_high:54342 (753ms)
```

**Nearby logs** (line 288394~288434):

```
    2026-04-30 03:15:02.388 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=13e0efb4-18cf-4d17-972e-0f9ab42928fb] Job created in REQUESTED state
    2026-04-30 03:15:02.441 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=e6669fd4-1cdc-405e-bb95-3232313a7a60] Dispatched to consolidated external API pipeline
    2026-04-30 03:15:02.470 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=e6669fd4-1cdc-405e-bb95-3232313a7a60
    2026-04-30 03:15:02.470 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExpectationCalcWorker:Process:미삼민 (379ms)
    2026-04-30 03:15:02.470 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54358 (379ms)
    2026-04-30 03:15:02.473 [external_api_queue-worker] [] WARN  m.e.i.p.EquipmentFetchProvider : [EquipmentProvider] Slow API fetch: ocid=139040bd652455a7a7baa3cf20a9d50d took 728ms
    2026-04-30 03:15:02.476 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: AdvisoryLock:ElectLeader:139040bd652455a7a7baa3cf20a9d50d (732ms)
>>> 2026-04-30 03:15:02.484 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqClient:Archive:expectation_calc_high:54342 (753ms)
    2026-04-30 03:15:02.484 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54342 (987ms)
    2026-04-30 03:15:02.484 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=13e0efb4-18cf-4d17-972e-0f9ab42928fb] Dispatched to consolidated external API pipeline
    2026-04-30 03:15:02.485 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=헤리, taskId=54364
    2026-04-30 03:15:02.496 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=13e0efb4-18cf-4d17-972e-0f9ab42928fb
    2026-04-30 03:15:02.497 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExpectationCalcWorker:Process:유츠메 (719ms)
    2026-04-30 03:15:02.497 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54355 (719ms)
    2026-04-30 03:15:02.523 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=6a15b553-769b-4177-903f-db13057f9ed5] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 03:15:02.523 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54355 (745ms)
    2026-04-30 03:15:02.523 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=뀨삐약, taskId=54365
    2026-04-30 03:15:02.589 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=6a15b553-769b-4177-903f-db13057f9ed5] Timeout detected: OCID_RESOLVING stale for >30s
    2026-04-30 03:15:02.589 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=081d6326-fe83-48d8-b2a7-1f4028177586] Dispatched to consolidated external API pipeline
    2026-04-30 03:15:02.600 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=081d6326-fe83-48d8-b2a7-1f4028177586
    2026-04-30 03:15:02.600 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqClient:Archive:expectation_calc_high:54366 (489ms)
    2026-04-30 03:15:02.601 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExpectationCalcWorker:Process:막좡 (798ms)
    2026-04-30 03:15:02.601 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54360 (798ms)
    2026-04-30 03:15:02.602 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:54366 (679ms)
    2026-04-30 03:15:02.602 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=개곰탱, taskId=54356
    2026-04-30 03:15:02.614 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=10fef7dd-a3f0-48f6-9643-a1f840e8a5e6] Calculation completed with result saved
```

**Source:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqClient.kt` — `performArchive()`

```kotlin
   357 |         )
   358 |     }
   359 | 
   360 |     private fun performArchive(queueName: String, messageId: Long): Boolean {
   361 |         val result = jdbcTemplate.queryForObject(
   362 |             "SELECT pgmq.archive(?, ?) as success",
   363 |             Boolean::class.java,
   364 |             queueName,
   365 |             messageId,
   366 |         ) ?: false
   367 | 
   368 |         if (result) {
   369 |             log.debug("✅ [PGMQ] Archived message: queue={}, msgId={}", queueName, messageId)
   370 |         }
   371 |         return result
   372 |     }
   373 | 
   374 |     private fun performDelete(queueName: String, messageId: Long): Boolean {
   375 |         val result = jdbcTemplate.queryForObject(
   376 |             "SELECT pgmq.delete(?, ?) as success",
   377 |             Boolean::class.java,
   378 |             queueName,
   379 |             messageId,
   380 |         ) ?: false
   381 | 
   382 |         if (result) {
   383 |             log.debug("🗑️ [PGMQ] Deleted message: queue={}, msgId={}", queueName, messageId)
   384 |         }
   385 |         return result
   386 |     }
   387 | 
   388 |     private fun performQueueLength(queueName: String): Long = jdbcTemplate.queryForObject(
   389 |         "SELECT queue_length FROM pgmq.metrics(?)",
   390 |         Long::class.java,
   391 |         queueName,
   392 |     ) ?: 0L

```

**Suspected cause:**

- `pgmq.archive()` = DELETE + INSERT per message → 2 DB writes
- Single-message archive instead of batch
- Called from `processSingleMessage` after pipeline completes → serial DB roundtrip

**Correlated events:**
- TimeoutScanner active in same window
- Lock contention in same window

### 7. `PgmqWorker:ProcessBatch:external_api_queue`

**Stats:** count=179, avg=276ms, p50=239ms, p95=443ms, p99=668ms, max=857ms, total=49.4s

**Max slow event** (857ms):

```
2026-04-30 04:37:02.438 [scheduler-1] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessBatch:external_api_queue (857ms)
```

**Nearby logs** (line 741123~741163):

```
    2026-04-30 04:37:02.389 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=2e12c64b-b936-40b0-a9eb-385e47f5d89c] Job created in REQUESTED state
    2026-04-30 04:37:02.396 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:70132 (352ms)
    2026-04-30 04:37:02.396 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=동선, taskId=70125
    2026-04-30 04:37:02.409 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqClient:Archive:expectation_calc_high:70117 (359ms)
    2026-04-30 04:37:02.409 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:70117 (427ms)
    2026-04-30 04:37:02.410 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=빵줘, taskId=70126
    2026-04-30 04:37:02.410 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=35cc865f-34c7-4a90-8fef-e3b3f6db0f26] Job created in REQUESTED state
    2026-04-30 04:37:02.423 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:70127 (575ms)
    2026-04-30 04:37:02.423 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=활일까요, taskId=70134
    2026-04-30 04:37:02.427 [external_api_queue-worker] [] WARN  m.e.i.p.EquipmentFetchProvider : [EquipmentProvider] Slow API fetch: ocid=cbdace71e21b76a4eaf3cd91062408e7 took 236ms
    2026-04-30 04:37:02.429 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PostgresQuery:Upsert:코루 (819ms)
    2026-04-30 04:37:02.429 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PostgresQuery:UpsertFromCalculation:코루 (820ms)
    2026-04-30 04:37:02.429 [external_api_queue-worker] [] INFO  m.e.i.worker.ExternalApiWorker : [jobId=30112839-6752-4211-9f32-da44e6ffb502] Pipeline completed
    2026-04-30 04:37:02.429 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExternalApiWorker:Pipeline:코루 (1914ms)
    2026-04-30 04:37:02.429 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=5ee92ebf-7e2c-4920-9ad9-75d3260e52bd] Calculation started by ExternalApiWorker
    2026-04-30 04:37:02.429 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:41477 (1914ms)
    2026-04-30 04:37:02.430 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: AdvisoryLock:ElectLeader:cbdace71e21b76a4eaf3cd91062408e7 (239ms)
>>> 2026-04-30 04:37:02.438 [scheduler-1] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessBatch:external_api_queue (857ms)
    2026-04-30 04:37:02.456 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=cf1996c6-18bd-4390-a5a5-b9113b737fd7] Job created in REQUESTED state
    2026-04-30 04:37:02.469 [scheduler-2] [] INFO  m.e.i.job.CalculationJobService : [jobId=5d7633bd-b14f-48ff-a396-b255d66681b3] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 04:37:02.474 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=2e12c64b-b936-40b0-a9eb-385e47f5d89c] Dispatched to consolidated external API pipeline
    2026-04-30 04:37:02.475 [scheduler-2] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=5d7633bd-b14f-48ff-a396-b255d66681b3] Timeout detected: OCID_RESOLVING stale for >30s
    2026-04-30 04:37:02.483 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=2e12c64b-b936-40b0-a9eb-385e47f5d89c
    2026-04-30 04:37:02.484 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=353fe125-4365-4b51-b81c-7bfbdba2f14a] Job created in REQUESTED state
    2026-04-30 04:37:02.497 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=7eb29641-1931-4575-9c19-7c71b48db8dd] Job created in REQUESTED state
    2026-04-30 04:37:02.501 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=35cc865f-34c7-4a90-8fef-e3b3f6db0f26] Dispatched to consolidated external API pipeline
    2026-04-30 04:37:02.509 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=35cc865f-34c7-4a90-8fef-e3b3f6db0f26
    2026-04-30 04:37:02.509 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExpectationCalcWorker:Process:Sukuny (250ms)
    2026-04-30 04:37:02.509 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:70122 (250ms)
    2026-04-30 04:37:02.509 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=353fe125-4365-4b51-b81c-7bfbdba2f14a] Dispatched to consolidated external API pipeline
    2026-04-30 04:37:02.515 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:70122 (257ms)
    2026-04-30 04:37:02.516 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=탈북, taskId=70130
    2026-04-30 04:37:02.517 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=353fe125-4365-4b51-b81c-7bfbdba2f14a
    2026-04-30 04:37:02.522 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=7eb29641-1931-4575-9c19-7c71b48db8dd] Dispatched to consolidated external API pipeline
    2026-04-30 04:37:02.525 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=9886ffd9-cbcb-4ada-80ba-43e1689a4d15] Calculation started by ExternalApiWorker
    2026-04-30 04:37:02.528 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=21c0e95b-b7ec-468c-aa2e-69f7444ec0dd] Job created in REQUESTED state
```

**Source:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt` — `processMessages()`

```kotlin
   157 |      * <p>4. 성공 시 archive, 실패 시 delete
   158 |      */
   159 |     @Scheduled(fixedDelayString = "\${pgmq.worker.common.polling-interval-ms:300}")
   160 |     fun processMessages() {
   161 |         if (!lifecycleWrapper.beforeTask()) return
   162 |         if (!workerSettings.enabled) {
   163 |             lifecycleWrapper.afterTask()
   164 |             return
   165 |         }
   166 | 
   167 |         val context = TaskContext.of("PgmqWorker", "ProcessBatch", queueName)
   168 | 
   169 |         executor.executeWithFinally(
   170 |             task = {
   171 |                 // Phase A: Flush accumulated messages if time window expired
   172 |                 if (sequentialBatchMs > 0 && supportsTwoPhase && accumulationBuffer.shouldFlush()) {
   173 |                     flushSequentialBatch()
   174 |                 }
   175 | 
   176 |                 // Phase B: Read new messages
   177 |                 val permits = inflightPermits.drainPermits()
   178 |                 if (permits <= 0) return@executeWithFinally
   179 | 
   180 |                 val batchSize = minOf(
   181 |                     workerSettings.batchSize ?: config.common.batchSize,
   182 |                     permits,
   183 |                 )
   184 |                 val visibilityTimeout = config.common.visibilityTimeoutSec
   185 | 
   186 |                 val messages = pgmqClient.read(queueName, payloadClass, batchSize, visibilityTimeout)
   187 | 
   188 |                 if (pollCounter.incrementAndGet() % 20 == 0) {
   189 |                     metrics.updateQueueDepth(pgmqClient.queueLength(queueName))
   190 |                 }
   191 | 
   192 |                 if (messages.isEmpty()) {

```

**Suspected cause:**

- Poll cycle calls `pgmqClient.read()` + `pgmqClient.queueLength()` → 2 DB roundtrips
- Serial execution: read → metrics → dispatch

**Correlated events:**
- TimeoutScanner active in same window
- Lock contention in same window

### 8. `PgmqWorker:ProcessBatch:expectation_calc_low`

**Stats:** count=149, avg=268ms, p50=254ms, p95=374ms, p99=494ms, max=575ms, total=39.9s

**Max slow event** (575ms):

```
2026-04-30 03:17:48.562 [scheduler-2] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessBatch:expectation_calc_low (575ms)
```

**Nearby logs** (line 343162~343202):

```
    2026-04-30 03:17:48.491 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=5c54d737-5ec9-4119-99d4-066fe3c98a73] Job created in REQUESTED state
    2026-04-30 03:17:48.497 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=09676f79-722f-4609-82c3-6388a60c6572] Job created in REQUESTED state
    2026-04-30 03:17:48.507 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=de332d0c-8af9-4a87-8b5f-c8551a614e31] Dispatched to consolidated external API pipeline
    2026-04-30 03:17:48.515 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=de332d0c-8af9-4a87-8b5f-c8551a614e31
    2026-04-30 03:17:48.517 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=a291f4f9-47a1-404b-afa7-c565c0fb8df5] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 03:17:48.520 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=5c54d737-5ec9-4119-99d4-066fe3c98a73] Dispatched to consolidated external API pipeline
    2026-04-30 03:17:48.521 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=09676f79-722f-4609-82c3-6388a60c6572] Dispatched to consolidated external API pipeline
    2026-04-30 03:17:48.523 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=a291f4f9-47a1-404b-afa7-c565c0fb8df5] Timeout detected: OCID_RESOLVING stale for >30s
    2026-04-30 03:17:48.526 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=5c54d737-5ec9-4119-99d4-066fe3c98a73
    2026-04-30 03:17:48.527 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=09676f79-722f-4609-82c3-6388a60c6572
    2026-04-30 03:17:48.533 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:59559 (241ms)
    2026-04-30 03:17:48.534 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=팔짜, taskId=59580
    2026-04-30 03:17:48.540 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=05162841-5e29-4fd3-8c2d-57f004b6d57a] Job created in REQUESTED state
    2026-04-30 03:17:48.548 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=3db04d5c-e606-4647-a544-02171a89032a] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 03:17:48.552 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=77776986-ff02-4845-a63d-472d56aabc6d] Job created in REQUESTED state
    2026-04-30 03:17:48.555 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=3db04d5c-e606-4647-a544-02171a89032a] Timeout detected: OCID_RESOLVING stale for >30s
>>> 2026-04-30 03:17:48.562 [scheduler-2] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessBatch:expectation_calc_low (575ms)
    2026-04-30 03:17:48.574 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=b4dbfe32-dc17-4ce7-bd20-82a702bbe9c0] Dispatched to consolidated external API pipeline
    2026-04-30 03:17:48.578 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=오지씨, taskId=59582
    2026-04-30 03:17:48.581 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=b4dbfe32-dc17-4ce7-bd20-82a702bbe9c0
    2026-04-30 03:17:48.599 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=05162841-5e29-4fd3-8c2d-57f004b6d57a] Dispatched to consolidated external API pipeline
    2026-04-30 03:17:48.607 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=778b970a-c0fe-4906-a9fe-adfef22ed8c6] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 03:17:48.606 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=05162841-5e29-4fd3-8c2d-57f004b6d57a
    2026-04-30 03:17:48.613 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=맵프, taskId=59583
    2026-04-30 03:17:48.615 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=778b970a-c0fe-4906-a9fe-adfef22ed8c6] Timeout detected: OCID_RESOLVING stale for >30s
    2026-04-30 03:17:48.637 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=d0df8ad6-46a7-49dc-99e1-f3bf3d856c39] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 03:17:48.640 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:59581 (200ms)
    2026-04-30 03:17:48.640 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=곽돌, taskId=59573
    2026-04-30 03:17:48.643 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=d0df8ad6-46a7-49dc-99e1-f3bf3d856c39] Timeout detected: OCID_RESOLVING stale for >30s
    2026-04-30 03:17:48.654 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=6efa1e8e-ff7c-47c3-a7ec-9746639980d1] Job created in REQUESTED state
    2026-04-30 03:17:48.660 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=38f2d4eb-0955-418b-999d-e384d97cfdb1] Job created in REQUESTED state
    2026-04-30 03:17:48.664 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=784e68e5-3948-4e39-9856-021079eddfb3] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 03:17:48.670 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=784e68e5-3948-4e39-9856-021079eddfb3] Timeout detected: OCID_RESOLVING stale for >30s
```

**Source:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt` — `processMessages()`

```kotlin
   157 |      * <p>4. 성공 시 archive, 실패 시 delete
   158 |      */
   159 |     @Scheduled(fixedDelayString = "\${pgmq.worker.common.polling-interval-ms:300}")
   160 |     fun processMessages() {
   161 |         if (!lifecycleWrapper.beforeTask()) return
   162 |         if (!workerSettings.enabled) {
   163 |             lifecycleWrapper.afterTask()
   164 |             return
   165 |         }
   166 | 
   167 |         val context = TaskContext.of("PgmqWorker", "ProcessBatch", queueName)
   168 | 
   169 |         executor.executeWithFinally(
   170 |             task = {
   171 |                 // Phase A: Flush accumulated messages if time window expired
   172 |                 if (sequentialBatchMs > 0 && supportsTwoPhase && accumulationBuffer.shouldFlush()) {
   173 |                     flushSequentialBatch()
   174 |                 }
   175 | 
   176 |                 // Phase B: Read new messages
   177 |                 val permits = inflightPermits.drainPermits()
   178 |                 if (permits <= 0) return@executeWithFinally
   179 | 
   180 |                 val batchSize = minOf(
   181 |                     workerSettings.batchSize ?: config.common.batchSize,
   182 |                     permits,
   183 |                 )
   184 |                 val visibilityTimeout = config.common.visibilityTimeoutSec
   185 | 
   186 |                 val messages = pgmqClient.read(queueName, payloadClass, batchSize, visibilityTimeout)
   187 | 
   188 |                 if (pollCounter.incrementAndGet() % 20 == 0) {
   189 |                     metrics.updateQueueDepth(pgmqClient.queueLength(queueName))
   190 |                 }
   191 | 
   192 |                 if (messages.isEmpty()) {

```

**Suspected cause:**

- Poll cycle calls `pgmqClient.read()` + `pgmqClient.queueLength()` → 2 DB roundtrips
- Serial execution: read → metrics → dispatch

**Correlated events:**
- TimeoutScanner active in same window

### 9. `PgmqWorker:ProcessBatch:expectation_calc_high`

**Stats:** count=148, avg=269ms, p50=244ms, p95=384ms, p99=639ms, max=767ms, total=39.9s

**Max slow event** (767ms):

```
2026-04-30 03:18:03.934 [scheduler-3] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessBatch:expectation_calc_high (767ms)
```

**Nearby logs** (line 348115~348155):

```
    2026-04-30 03:18:03.881 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=f6224ae8-2f8b-488d-b78f-d9e01bf9b4d4
    2026-04-30 03:18:03.881 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExpectationCalcWorker:Process:비공감 (437ms)
    2026-04-30 03:18:03.881 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:59983 (437ms)
    2026-04-30 03:18:03.885 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=7b470abc-7278-42b6-83ed-57eeb71f6a06] Calculation completed with result saved
    2026-04-30 03:18:03.893 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:30970 (462ms)
    2026-04-30 03:18:03.902 [external_api_queue-worker] [] INFO  m.e.i.worker.ExternalApiWorker : [jobId=7b470abc-7278-42b6-83ed-57eeb71f6a06] Pipeline completed
    2026-04-30 03:18:03.903 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:59997 (467ms)
    2026-04-30 03:18:03.903 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExternalApiWorker:Pipeline:신기술 (1553ms)
    2026-04-30 03:18:03.903 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:31013 (1553ms)
    2026-04-30 03:18:03.903 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=간취, taskId=59993
    2026-04-30 03:18:03.913 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=3f18b1ef-c4b9-48ac-82d3-34b7d9c81ab9] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 03:18:03.913 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqClient:Archive:external_api_queue:30969 (459ms)
    2026-04-30 03:18:03.914 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:30969 (517ms)
    2026-04-30 03:18:03.915 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqClient:Archive:expectation_calc_high:59971 (498ms)
    2026-04-30 03:18:03.915 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:59971 (700ms)
    2026-04-30 03:18:03.916 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=연니찌, taskId=59982
    2026-04-30 03:18:03.923 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=3f18b1ef-c4b9-48ac-82d3-34b7d9c81ab9] Timeout detected: OCID_RESOLVING stale for >30s
    2026-04-30 03:18:03.934 [scheduler-3] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqClient:QueueLength:expectation_calc_high (200ms)
>>> 2026-04-30 03:18:03.934 [scheduler-3] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessBatch:expectation_calc_high (767ms)
    2026-04-30 03:18:03.940 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=0e3edd8b-854e-4746-a951-70b7aecba5af] Job created in REQUESTED state
    2026-04-30 03:18:03.946 [expectation_calc_high-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:59983 (502ms)
    2026-04-30 03:18:03.946 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=초후o, taskId=59984
    2026-04-30 03:18:03.950 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=13623711-4ba6-463a-83a5-bf995ef72ee9] Job created in REQUESTED state
    2026-04-30 03:18:03.951 [scheduler-1] [] INFO  m.e.i.job.CalculationJobService : [jobId=aa1f7d08-d79a-44c8-b5a6-7bda6c0cd7a2] OCID resolve retry (attempt 1): OCID_RESOLVE_TIMEOUT
    2026-04-30 03:18:03.964 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=26d0f0b4-0ed1-46de-b117-15951ab940e0] Job created in REQUESTED state
    2026-04-30 03:18:03.970 [scheduler-1] [] WARN  m.e.i.j.CalculationJobTimeoutScanner : [jobId=aa1f7d08-d79a-44c8-b5a6-7bda6c0cd7a2] Timeout detected: OCID_RESOLVING stale for >30s
    2026-04-30 03:18:03.977 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=0e3edd8b-854e-4746-a951-70b7aecba5af] Dispatched to consolidated external API pipeline
    2026-04-30 03:18:03.979 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:31013 (1628ms)
    2026-04-30 03:18:03.981 [external_api_queue-worker] [] INFO  m.e.i.e.impl.RealNexonApiClient : [NexonApi] OCID lookup: characterName=쥬다잉
    2026-04-30 03:18:03.985 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Job dispatched to external API pipeline: jobId=0e3edd8b-854e-4746-a951-70b7aecba5af
    2026-04-30 03:18:03.994 [expectation_calc_high-worker] [] INFO  m.e.i.worker.ExpectationCalcWorker : [ExpectationCalcWorker] Creating job: userIgn=목린, taskId=59986
    2026-04-30 03:18:03.995 [external_api_queue-worker] [] INFO  m.e.i.e.impl.RealNexonApiClient : [NexonApi] OCID lookup: characterName=홍패파임
    2026-04-30 03:18:03.998 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=79115faa-7f44-4542-9572-62a236f9b687] Job created in REQUESTED state
    2026-04-30 03:18:04.005 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=d68647a8-0a42-4a38-b3cf-40e4759b3290] Job created in REQUESTED state
    2026-04-30 03:18:04.008 [expectation_calc_high-worker] [] INFO  m.e.i.job.CalculationJobService : [jobId=26d0f0b4-0ed1-46de-b117-15951ab940e0] Dispatched to consolidated external API pipeline
    2026-04-30 03:18:04.012 [external_api_queue-worker] [] INFO  m.e.i.c.t.AbstractTieredCacheService : [Cache] MISS | cache=equipment | key=674ff1c71903fb3e5942501305e8a359efe8d04e6d233bd35cf2fabdeb93fb0d
```

**Source:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt` — `processMessages()`

```kotlin
   157 |      * <p>4. 성공 시 archive, 실패 시 delete
   158 |      */
   159 |     @Scheduled(fixedDelayString = "\${pgmq.worker.common.polling-interval-ms:300}")
   160 |     fun processMessages() {
   161 |         if (!lifecycleWrapper.beforeTask()) return
   162 |         if (!workerSettings.enabled) {
   163 |             lifecycleWrapper.afterTask()
   164 |             return
   165 |         }
   166 | 
   167 |         val context = TaskContext.of("PgmqWorker", "ProcessBatch", queueName)
   168 | 
   169 |         executor.executeWithFinally(
   170 |             task = {
   171 |                 // Phase A: Flush accumulated messages if time window expired
   172 |                 if (sequentialBatchMs > 0 && supportsTwoPhase && accumulationBuffer.shouldFlush()) {
   173 |                     flushSequentialBatch()
   174 |                 }
   175 | 
   176 |                 // Phase B: Read new messages
   177 |                 val permits = inflightPermits.drainPermits()
   178 |                 if (permits <= 0) return@executeWithFinally
   179 | 
   180 |                 val batchSize = minOf(
   181 |                     workerSettings.batchSize ?: config.common.batchSize,
   182 |                     permits,
   183 |                 )
   184 |                 val visibilityTimeout = config.common.visibilityTimeoutSec
   185 | 
   186 |                 val messages = pgmqClient.read(queueName, payloadClass, batchSize, visibilityTimeout)
   187 | 
   188 |                 if (pollCounter.incrementAndGet() % 20 == 0) {
   189 |                     metrics.updateQueueDepth(pgmqClient.queueLength(queueName))
   190 |                 }
   191 | 
   192 |                 if (messages.isEmpty()) {

```

**Suspected cause:**

- Poll cycle calls `pgmqClient.read()` + `pgmqClient.queueLength()` → 2 DB roundtrips
- Serial execution: read → metrics → dispatch

**Correlated events:**
- TimeoutScanner active in same window

### 10. `GracefulShutdownHook:CoordinatorRun`

**Stats:** count=5, avg=6090ms, p50=5194ms, p95=14462ms, p99=14462ms, max=14462ms, total=30.4s

**Max slow event** (14462ms):

```
2026-04-30 04:28:02.722 [shutdown-coordinator] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: GracefulShutdownHook:CoordinatorRun (14462ms)
```

**Nearby logs** (line 641729~641769):

```
>>> 2026-04-30 04:28:02.722 [shutdown-coordinator] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: GracefulShutdownHook:CoordinatorRun (14462ms)
    2026-04-30 04:28:02.734 [external_api_queue-worker] [] INFO  m.e.i.e.impl.RealNexonApiClient : [NexonApi] OCID lookup: characterName=별빛로안
    2026-04-30 04:28:02.755 [external_api_queue-worker] [] INFO  m.e.i.c.t.AbstractTieredCacheService : [Cache] MISS | cache=equipment | key=8f018aa52671d720889457936af814ffefe8d04e6d233bd35cf2fabdeb93fb0d
    2026-04-30 04:28:02.762 [external_api_queue-worker] [] INFO  m.e.i.c.t.AbstractTieredCacheService : [Cache] MISS | cache=equipment | key=dccaa6754aedd8e8e37cfc6bcaa36674
    2026-04-30 04:28:02.763 [external_api_queue-worker] [] INFO  m.e.i.l.PostgresAdvisoryLockStrategy : 👑 [Leader] Acquired xact lock for key: 8f018aa52671d720889457936af814ffefe8d04e6d233bd35cf2fabdeb93fb0d
    2026-04-30 04:28:02.764 [external_api_queue-worker] [] INFO  m.e.i.a.aspect.NexonDataCacheAspect : [Leader] 캐시 갱신 시작: 8f018aa52671d720889457936af814ffefe8d04e6d233bd35cf2fabdeb93fb0d
    2026-04-30 04:28:02.765 [external_api_queue-worker] [] INFO  m.e.i.e.impl.RealNexonApiClient : [NexonApi] Equipment data request (Cache Miss): ocid=8f018aa52671d720889457936af814ffefe8d04e6d233bd35cf2fabdeb93fb0d
    2026-04-30 04:28:02.771 [external_api_queue-worker] [] INFO  m.e.i.worker.ExternalApiWorker : [jobId=90a72df9-dce6-4abe-b72c-5e0aeff601cf] Pipeline completed
    2026-04-30 04:28:02.771 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: ExternalApiWorker:Pipeline:쭈놈 (791ms)
    2026-04-30 04:28:02.771 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:40313 (791ms)
    2026-04-30 04:28:02.777 [external_api_queue-worker] [] INFO  m.e.i.l.PostgresAdvisoryLockStrategy : 👑 [Leader] Acquired xact lock for key: dccaa6754aedd8e8e37cfc6bcaa36674
    2026-04-30 04:28:02.777 [external_api_queue-worker] [] INFO  m.e.i.a.aspect.NexonDataCacheAspect : [Leader] 캐시 갱신 시작: dccaa6754aedd8e8e37cfc6bcaa36674
    2026-04-30 04:28:02.778 [external_api_queue-worker] [] INFO  m.e.i.e.impl.RealNexonApiClient : [NexonApi] Equipment data request (Cache Miss): ocid=dccaa6754aedd8e8e37cfc6bcaa36674
    2026-04-30 04:28:02.778 [external_api_queue-worker] [] WARN  m.e.i.executor.policy.LoggingPolicy : [Logging] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:40313 (799ms)
    2026-04-30 04:28:02.784 [external_api_queue-worker] [] INFO  m.e.i.j.CalculationExecutionService : [jobId=9bbcc28b-0bbe-4228-b482-a1bb6378dd23] Calculation completed with result saved
    2026-04-30 04:28:02.791 [external_api_queue-worker] [] INFO  m.e.i.e.impl.RealNexonApiClient : [NexonApi] OCID lookup: characterName=후라칸
    2026-04-30 04:28:02.802 [external_api_queue-worker] [] INFO  m.e.i.c.t.AbstractTieredCacheService : [Cache] MISS | cache=equipment | key=9dbeec059f03313a3120533250e987da
    2026-04-30 04:28:02.815 [external_api_queue-worker] [] INFO  m.e.i.l.PostgresAdvisoryLockStrategy : 👑 [Leader] Acquired xact lock for key: 9dbeec059f03313a3120533250e987da
    2026-04-30 04:28:02.816 [external_api_queue-worker] [] INFO  m.e.i.a.aspect.NexonDataCacheAspect : [Leader] 캐시 갱신 시작: 9dbeec059f03313a3120533250e987da
    2026-04-30 04:28:02.816 [external_api_queue-worker] [] INFO  m.e.i.e.impl.RealNexonApiClient : [NexonApi] Equipment data request (Cache Miss): ocid=9dbeec059f03313a3120533250e987da
    2026-04-30 04:28:02.822 [external_api_queue-worker] [] INFO  m.e.i.c.t.AbstractTieredCacheService : [Cache] MISS | cache=equipment | key=6a211e281015aed99d827020bc2a2f2eefe8d04e6d233bd35cf2fabdeb93fb0d
```

**Source:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/lifecycle/GracefulShutdownHook.kt` — `run()`

```kotlin
    41 |     }
    42 | 
    43 |     @Volatile
    44 |     private var running = false
    45 | 
    46 |     override fun start() {
    47 |         this.running = true
    48 |         logger.debug("[GracefulShutdownHook] Started")
    49 |     }
    50 | 
    51 |     override fun stop() {
    52 |         val context = TaskContext.of("GracefulShutdownHook", "Main")
    53 |         val startNanos = System.nanoTime()
    54 | 
    55 |         executor.executeWithFinally(
    56 |             {
    57 |                 logger.warn("[GracefulShutdownHook] =============== Shutdown 시작 ===============")
    58 | 
    59 |                 val completed = executeWithTimeout()
    60 | 
    61 |                 if (completed) {
    62 |                     shutdownSuccessCounter?.increment()
    63 |                     logger.warn("[GracefulShutdownHook] =============== Shutdown 완료 ===============")
    64 |                 } else {
    65 |                     shutdownTimeoutCounter?.increment()
    66 |                     logger.error("[GracefulShutdownHook] =============== Shutdown 타임아웃 ===============")
    67 |                 }
    68 | 
    69 |                 null
    70 |             },
    71 |             {
    72 |                 this.running = false
    73 |                 shutdownTimer?.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS)
    74 |             },
    75 |             context,
    76 |         )

```

**Suspected cause:**

- Graceful shutdown draining in-flight tasks — not a production concern

**Correlated events:**
- Lock contention in same window
