# Slow Task Analysis (2026-04-30)

Load test 10K requests, worker-pool-size=16 환경에서 `Slow task detected` 로그 기반 분석.

## Top 10 by Max Duration (느린 순)

| # | Task | Max | Avg | Count | Root Cause |
|---|------|-----|-----|-------|------------|
| 1 | TimeoutScanner:Scan:stale_jobs | **281,029ms** | 100,279ms | 18x | 30초마다 stale job 스캔 → 재시도 dispatch 중복 |
| 2 | DlqReplayWorker:Replay | **38,270ms** | 12,242ms | 4x | 6개 큐 아카이브 테이블 순차 스캔 |
| 3 | PgmqWorker:ProcessMessage:external_api_queue | **7,294ms** | 731ms | 25,611x | Nexon API + DB I/O 파이프라인 |
| 4 | PgmqWorker:ProcessMessage:expectation_calc_high | **987ms** | 269ms | 9,092x | 계산 + DB write |
| 5 | PgmqWorker:ProcessBatch:external_api_queue | **857ms** | 278ms | 156x | read + dispatch 오버헤드 |
| 6 | PgmqWorker:ProcessBatch:expectation_calc_high | **767ms** | 274ms | 128x | read + dispatch 오버헤드 |
| 7 | PgmqClient:Read:external_api_queue | **777ms** | 294ms | 36x | pgmq.read() SQL 쿼리 |
| 8 | PgmqWorker:ProcessBatch:expectation_calc_low | **575ms** | 264ms | 125x | read + dispatch 오버헤드 |
| 9 | PgmqClient:Read:expectation_calc_high | **566ms** | 258ms | 30x | pgmq.read() SQL 쿼리 |
| 10 | OutboxCompensatingScanner:Scan | **861ms** | 485ms | 13x | orphaned job 보상 이벤트 |

## Top 10 by Frequency (많이 찍힌 순)

| # | Task | Count | Max | Avg |
|---|------|-------|-----|-----|
| 1 | ExternalApiWorker:Pipeline:* | **11,913x** | ~7s | ~700ms |
| 2 | PgmqWorker:ProcessMessage:external_api_queue | **25,611x** | 7,294ms | 731ms |
| 3 | PgmqWorker:ProcessMessage:expectation_calc_high | **9,092x** | 987ms | 269ms |
| 4 | PgmqWorker:ProcessBatch:external_api_queue | **156x** | 857ms | 278ms |
| 5 | PgmqWorker:ProcessBatch:expectation_calc_high | **128x** | 767ms | 274ms |
| 6 | PgmqWorker:ProcessBatch:expectation_calc_low | **125x** | 575ms | 264ms |
| 7 | PgmqClient:Read:external_api_queue | **36x** | 777ms | 294ms |
| 8 | PgmqClient:Read:expectation_calc_high | **30x** | 566ms | 258ms |
| 9 | PgmqClient:Read:expectation_calc_low | **23x** | 480ms | 251ms |
| 10 | PgmqClient:Archive:expectation_calc_high | **253x** | 753ms | 255ms |

---

## 각 Task 코드 로직 분석

### 1. TimeoutScanner:Scan:stale_jobs (281s max, 100s avg)

**파일:** `module-infra/.../job/CalculationJobTimeoutScanner.kt:19-42`

```kotlin
@Scheduled(fixedDelayString = "\${job.scanner.timeout-interval-ms:30000}")
fun scanStaleJobs() {
    executor.executeVoid({
        val staleOcidResolving = jobPort.findStaleJobs(OCID_RESOLVING, 30)
        for (job in staleOcidResolving) {
            jobService.handleOcidFailure(job.jobId, "OCID_RESOLVE_TIMEOUT", ...)
        }
        val staleApiRequested = jobPort.findStaleJobs(API_REQUESTED, 180)
        for (job in staleApiRequested) {
            jobService.handleApiFailure(job.jobId, "API_TIMEOUT", ...)
        }
        val staleRetrying = jobPort.findStaleJobs(RETRYING, 60)
        for (job in staleRetrying) {
            jobService.handleApiFailure(job.jobId, "RETRY_TIMEOUT", ...)
        }
    }, context)
}
```

**문제점:**
- 30초마다 3종류 stale job을 DB에서 조회
- `handleOcidFailure` / `handleApiFailure` 내부에서 `pgmqClient.send()`로 재시도 메시지를 큐에 다시 넣음
- 이미 ExternalApiWorker가 처리 중인 job을 timeout으로 오인하여 **중복 메시지** 발생
- 로드테스트 중 수백 개의 "OCID resolve retry (attempt 2): OCID_RESOLVE_TIMEOUT" 로그 확인됨
- 281초 max는 stale job이 대량으로 쌓여 처리에 오래 걸린 case

**handleOcidFailure 로직** (`CalculationJobService.kt:115-128`):
```kotlin
fun handleOcidFailure(jobId: UUID, errorCode: String, errorMessage: String) {
    val job = jobPort.findJobById(jobId) ?: return
    if (job.retryCount >= job.maxRetries) {
        jobPort.markFailed(jobId, errorCode, errorMessage)
    } else {
        val retried = jobPort.incrementRetryForOcid(jobId, errorCode)
        if (retried) {
            // ← 여기서 ocidResolveTopic에 재시도 메시지 발행 → ExternalApiWorker로 다시 들어옴
            eventAppender.append(ocidResolveTopic, OcidResolveEventFactory.create(...))
        }
    }
}
```

**개선 방안:**
- TimeoutScanner가 dispatch하기 전에 job 상태를 다시 확인 (이미 COMPLETED면 스킵)
- ExternalApiWorker의 early exit check가 있지만, 중복 API 호출 전에 필터링되지 않음
- Scanner interval을 30s → 60s 이상으로 늘리거나, batch size 제한

---

### 2. DlqReplayWorker:Replay (38s max, 12s avg)

**파일:** `module-infra/.../pgmq/DlqReplayWorker.kt:59-84`

```kotlin
@Scheduled(fixedDelayString = "\${pgmq.dlq.replay-interval-ms:3600000}")
fun replayDeadLetters() {
    for (queueName in QUEUE_NAMES) {  // 6개 큐 순차 처리
        discoverAndTrack(queueName)       // archive 테이블에서 DLQ 메시지 발견
        totalReplayed += replayEligible(queueName)  // 백오프 경과한 메시지 재발행
        totalPermanent += alertPermanentFailures(queueName)
    }
}
```

**문제점:**
- 6개 큐의 archive 테이블을 **순차 스캔** (병렬화 안됨)
- `discoverAndTrack`에서 각 큐의 archive 테이블에 대해 `SELECT ... WHERE read_ct > 1` 쿼리
- `replayEligible`에서 `dlq_replay_meta` 테이블 조회 후 메시지별 개별 재발행
- 각 큐당 여러 DB 쿼리가 직렬로 실행되어 38초까지 걸림

**개선 방안:**
- 큐별 스캔을 병렬화 (coroutine 또는 CF fan-out)
- 배치 재발행 (개별 send 대신 batch send)

---

### 3. PgmqWorker:ProcessMessage:external_api_queue (7.3s max, 731ms avg, 25,611x)

**파일:** `module-infra/.../pgmq/PgmqWorker.kt:415-461`

```kotlin
private fun processSingleMessage(message: PgmqMessage<T>): Boolean {
    return executor.executeWithFinally(
        task = {
            val success = executor.executeOrDefault({ process(message) }, false, context)
            when {
                success -> pgmqClient.archive(queueName, message.messageId)  // ← DB 왕복
                message.isRetryable(maxRetries) -> onProcessingFailed(message)
                else -> pgmqClient.archive(queueName, message.messageId)    // ← DB 왕복
            }
            success
        },
        finallyBlock = {
            metrics.concurrentDecrement()
            metrics.inflightDecrement()
            inflightPermits.release()
        },
    )
}
```

**문제점:**
- `process(message)` → `ExternalApiWorker.processPipeline()` 호출 (OCID + Equipment API + snapshot save + calculate + view projection)
- 파이프라인 완료 후 `pgmqClient.archive()` → **추가 DB 왕복** (~250ms)
- 7.3초 max는 Nexon API 응답 지연 시 발생 (API 자체가 200-500ms + lock 경합)
- avg 731ms = API 500ms + DB I/O ~200ms

**processPipeline 호출 chain:**
```
processSingleMessage()
  → process(message)                    // ExternalApiWorker.processPipeline()
    → resolveOcid()                     // Nexon API ~200ms
    → equipmentFetchProvider.fetchWithCache()  // Nexon API ~300ms + AdvisoryLock
    → snapshotStore.put()               // DB write
    → calculationInputPort.save()       // DB write
    → pureCalculationPort.calculate()   // CPU ~ms
    → executionService.completeCalculationWithResult()  // DB write
    → viewQueryPort.upsertFromCalculation()            // DB write
  → pgmqClient.archive()                // DB write ~250ms
```

**개선 방안:**
- archive를 비동기/배치로 전환 (pipeline 완료 후 즉시 다음 메시지 처리)
- pipeline 내 DB write를 batch로 묶기

---

### 4. PgmqWorker:ProcessMessage:expectation_calc_high (987ms max, 269ms avg, 9,092x)

**파일:** `module-infra/.../pgmq/PgmqWorker.kt:415-461` (동일, 다른 큐)

**호출 chain:** `ExpectationCalcWorker.process()` → expectation 계산 + DB write

**문제점:**
- 269ms avg 중 대부분이 DB write (계산 자체는 CPU-bound로 수 ms)
- 9,092x 빈도 = external_api_queue 파이프라인에서 expectation_calc_high에 dispatch하는 구조
- ExternalApiWorker가 inline으로 계산하므로 이 큐는 점차 감소 예상

---

### 5-6. PgmqWorker:ProcessBatch:* (857ms/767ms max, ~275ms avg)

**파일:** `module-infra/.../pgmq/PgmqWorker.kt:158-223`

```kotlin
@Scheduled(fixedDelayString = "\${pgmq.worker.common.polling-interval-ms:300}")
fun processMessages() {
    val permits = inflightPermits.drainPermits()
    if (permits <= 0) return

    val messages = pgmqClient.read(queueName, payloadClass, batchSize, visibilityTimeout)  // ← ~250ms
    metrics.updateQueueDepth(pgmqClient.queueLength(queueName))  // ← ~250ms

    // route to processing mode
    if (sequentialBatchMs > 0 && supportsTwoPhase) { ... }
    else if (supportsTwoPhase) { processBatchPipelined(messages) }
    else { processBatchSinglePhase(messages) }
}
```

**문제점:**
- 매 poll cycle마다 `pgmqClient.read()` + `pgmqClient.queueLength()` = **2번의 DB 왕복** (~500ms 합산)
- 300ms polling interval인데 DB 왕복만 500ms → 실제 처리 대비 오버헤드 큼
- `queueLength`는 metrics 업데이트용인데 매 poll마다 호출할 필요 없음

**개선 방안:**
- `queueLength` 호출을 N번에 1회로 감소 또는 비동기로 분리
- read + queueLength를 하나의 쿼리로 통합

---

### 7. PgmqClient:Read:external_api_queue (777ms max, 294ms avg)

**파일:** `module-infra/.../pgmq/PgmqClient.kt:300-338`

```kotlin
private fun <T : Any> performRead(queueName: String, clazz: Class<T>, batchSize: Int, visibilityTimeoutSec: Int): List<PgmqMessage<T>> {
    val messages = jdbcTemplate.query(
        "SELECT msg_id, read_ct, enqueued_at, vt, message FROM pgmq.read(?, ?, ?)",
        ...
    )
    // JSONB → Jackson deserialize per message
}
```

**문제점:**
- `pgmq.read()` SQL 함수 자체가 SKIP LOCKED 기반으로 동작
- Supabase pgBouncer transaction mode (port 6543) 경유 → connection routing latency
- 16개 worker가 동시에 read → **connection pool 경합**
- JSONB deserialization 오버헤드 (메시지당 Jackson parse)

**개선 방안:**
- connection pool size 증설 (현재 HikariCP maximumPoolSize 확인 필요)
- read batch size 최적화

---

### 8. AdvisoryLock:ElectLeader (~294ms)

**파일:** `module-infra/.../lock/PostgresAdvisoryLockStrategy.kt:101-144`

```kotlin
override fun <T> executeWithLeaderElection(key: String, waitTimeSeconds: Int, leaderTask: ThrowingSupplier<T>, followerTask: ThrowingSupplier<T>): T {
    val leaderResult = lockTransactionTemplate.execute {
        val acquired = tryAcquireXactLock(lockId)  // pg_try_advisory_xact_lock
        if (acquired) { leaderTask.get() } else { null }
    }
    // Follower: poll with 100ms sleep until leader's xact commits
    while (System.currentTimeMillis() - startTime < timeoutMs) {
        val leaderDone = lockTransactionTemplate.execute { tryAcquireXactLock(lockId) }
        if (leaderDone) break
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MS))  // 100ms
    }
    return followerTask.get()
}
```

**문제점:**
- Equipment fetch 시 leader election 사용 — leader만 API 호출, follower는 L2 폴링
- Leader가 API 호출 (~300ms) 하는 동안 follower가 100ms 간격으로 lock 획득 시도
- 16개 worker가 같은 OCID를 동시에 처리하면 1 leader + 15 follower가 각각 DB 쿼리
- 294ms = leader의 API 호출 시간 + transaction commit 대기

**개선 방안:**
- follower poll interval을 100ms → 50ms로 단축하면 대기 시간 감소
- 또는 LISTEN/NOTIFY 기반으로 follower가 polling 대신 이벤트 수신

---

### 9. OutboxCompensatingScanner:Scan (861ms max, 485ms avg)

**파일:** `module-infra/.../job/OutboxCompensatingScanner.kt:19-33`

```kotlin
@Scheduled(fixedDelay = 60000, initialDelay = 30000)
fun scan() {
    val orphaned = jobPort.findCompletedJobsMissingOutboxEvents(50)
    for (jobId in orphaned) {
        outboxPort.insertIfAbsent("CALCULATION_COMPLETED", jobId, payload)
    }
}
```

**문제점:**
- 60초마다 "완료됐지만 outbox event가 없는 job"을 조회
- ExternalApiWorker가 inline projection을 하므로 outbox 경로를 거치지 않는 job이 orphan으로 감지될 수 있음
- 개별 INSERT가 순차 실행

**개선 방안:**
- inline projection 도입 후 이 scanner의 역할이 축소됨
- batch INSERT로 전환

---

### 10. PgmqClient:Archive:* (753ms max, 255ms avg, 253x)

**파일:** `module-infra/.../pgmq/PgmqClient.kt:360-372`

```kotlin
private fun performArchive(queueName: String, messageId: Long): Boolean {
    val result = jdbcTemplate.queryForObject(
        "SELECT pgmq.archive(?, ?) as success", Boolean::class.java, queueName, messageId
    )
    return result ?: false
}
```

**문제점:**
- 메시지 처리 완료 후 매번 개별 archive 쿼리 실행
- `pgmq.archive()` = DELETE from queue + INSERT into archive → 2회 DB write
- 파이프라인 당 1회 archive = 10K 메시지 시 10K번의 archive 쿼리
- batch archive variant가 존재하나 single-phase mode에서는 미사용

**개선 방안:**
- single-phase mode에서도 batch archive 사용
- archive를 비동기 큐에 넣고 batch로 처리

---

## 종합 개선 우선순위

| Priority | Task | Impact | Effort |
|----------|------|--------|--------|
| P0 | TimeoutScanner 중복 dispatch 방지 | 큐 적체 감소 | Low |
| P0 | PgmqClient.read/archive DB 왕복 감소 | 처리량 2x 향상 가능 | Medium |
| P1 | ProcessBatch에서 queueLength 호출 빈도 감소 | poll 오버헤드 50% 감소 | Low |
| P1 | Archive batch화 (single-phase mode) | DB 쿼리 수 10x 감소 | Medium |
| P2 | DlqReplayWorker 큐별 병렬 스캔 | replay 소요 시간 6x 감소 | Low |
| P2 | AdvisoryLock follower poll interval 단축 | lock 대기 시간 감소 | Low |
