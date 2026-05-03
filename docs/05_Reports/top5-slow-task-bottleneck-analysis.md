# Top 5 Slow Task 병목 분석 및 개선 방향

app.log 92,331개 slow event 기반. **98%가 단 2개 루트 원인**에서 발생.

---

## 1. `PgmqWorker:ProcessMessage:external_api_queue` (총 30,807s)

| count | avg | p50 | p95 | p99 | max |
|-------|-----|-----|-----|-----|-----|
| 39,685 | 776ms | 698ms | 1,306ms | 1,774ms | 7,294ms |

### 대표 slow event (7,294ms)

```log
03:13:23.484 [external_api_queue-worker] Slow task detected: ExternalApiWorker:Pipeline:발톱 (7253ms)
03:13:23.484 [external_api_queue-worker] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:29991 (7254ms)
03:13:23.524 [external_api_queue-worker] Slow task detected: PgmqWorker:ProcessMessage:external_api_queue:29991 (7294ms)
03:13:23.535 [external_api_queue-worker] 👑 [Leader] Acquired xact lock
03:13:23.550 [external_api_queue-worker] Pipeline completed → 다음 메시지 시작
```

### 소스코드: `PgmqWorker.kt:418-461` (processSingleMessage)

```kotlin
private fun processSingleMessage(message: PgmqMessage<T>): Boolean {
    return executor.executeWithFinally(
        task = {
            metrics.concurrentIncrement()
            val success = executor.executeOrDefault(
                { process(message) },   // ← ExternalApiWorker.processPipeline() 호출
                false,
                context,
            )
            when {
                success -> {
                    pgmqClient.archive(queueName, message.messageId)  // ← +250ms DB roundtrip
                    metrics.success.increment()
                }
                message.isRetryable(maxRetries) -> onProcessingFailed(message)
                else -> {
                    pgmqClient.archive(queueName, message.messageId)  // ← +250ms DB roundtrip
                    metrics.dlq.increment()
                }
            }
            success
        },
        finallyBlock = {
            metrics.concurrentDecrement()
            metrics.inflightDecrement()
            inflightPermits.release()       // ← semaphore 해제 (다음 메시지 가능)
        },
    )
}
```

### 병목 chain

```text
processSingleMessage()
  → process()                              // ExternalApiWorker.processPipeline()
    → resolveOcid()                        // Nexon API ~200ms
    → equipmentFetchProvider.fetchWithCache() // Nexon API ~300ms + AdvisoryLock ~280ms
    → snapshotStore.put()                  // DB write ~50ms
    → calculationInputPort.save()          // DB write ~50ms
    → pureCalculationPort.calculate()      // CPU ~5ms
    → executionService.completeCalculationWithResult()  // DB write ~50ms
    → viewQueryPort.upsertFromCalculation()             // DB write ~50ms
  → pgmqClient.archive()                   // DB write ~250ms  ← 병목!
  finally: semaphore.release()
```

**총 ~1,050ms/건 = API 500ms + DB writes 300ms + archive 250ms**

### 개선안

- Pipeline DB write 3→1 트랜잭션 묶기 (snapshotStore + calculationInput + result)
- `archive` 비동기 분리 또는 배치 archive로 지연 제거

---

## 2. `ExternalApiWorker:Pipeline:*` (총 14,697s)

| count | avg | p50 | p95 | p99 | max |
|-------|-----|-----|-----|-----|-----|
| 18,516 | 794ms | 707ms | 1,291ms | 1,784ms | 7,253ms |

### 대표 slow event (7,253ms)

```log
03:13:23.476 [external_api_queue-worker] Calculation completed with result saved
03:13:23.483 [external_api_queue-worker] Pipeline completed         ← 이전 job 완료
03:13:23.484 [external_api_queue-worker] Slow task detected: ExternalApiWorker:Pipeline:발톱 (7253ms)
03:13:23.535 [external_api_queue-worker] 👑 [Leader] Acquired xact lock  ← 다음 job의 lock
03:13:23.535 [external_api_queue-worker] [Leader] 캐시 갱신 시작
03:13:23.537 [external_api_queue-worker] Equipment data request (Cache Miss)
```

주변 로그에서 **AdvisoryLock 경합**이 동시 발생. Leader가 xact lock 잡고 API 호출하는 동안(~300ms) follower들이 100ms 간격으로 DB polling.

### 소스코드: `ExternalApiWorker.kt:103-204` (processPipeline)

```kotlin
private fun processPipeline(payload: ExternalApiJobPayload) {
    val jobId = UUID.fromString(payload.jobId)

    // Early exit: 이미 완료된 job이면 스킵
    val existingJob = jobPort.findJobById(jobId)                    // DB read
    if (existingJob != null && existingJob.status != OCID_RESOLVING
        && existingJob.status != REQUESTED) return

    // Step 1: Resolve OCID (Nexon API ~200ms)
    val ocid = resolveOcid(jobId, payload.userIgn)                  // API + DB write

    // Step 2: Fetch equipment data (Nexon API ~300ms + Lock ~280ms)
    val equipmentResponse = equipmentFetchProvider.fetchWithCache(ocid)  // API + Lock

    // Step 3: Save snapshot + CalculationInput
    snapshotStore.put(snapshot, snapshotData)                       // DB write
    calculationInputPort.save(calcInput)                            // DB write
    jobService.saveSnapshotInPlace(snapshotEntity)                  // DB write
    jobService.markSnapshotReadyInPlace(jobId, snapshotId)          // DB write

    // Step 4: Calculate
    executionService.startCalculation(jobId, "ExternalApiWorker")   // DB read + write
    val calcResult = pureCalculationPort.calculate(input)           // CPU ~5ms
    executionService.completeCalculationWithResult(...)              // DB write

    // Step 5: Inline view projection
    viewQueryPort.upsertFromCalculation(...)                        // DB write
}
```

**파이프라인당 DB write 7회**: findJobById, resolveOcidInPlace, snapshotStore.put, saveSnapshotInPlace, markSnapshotReadyInPlace, completeCalculationWithResult, upsertFromCalculation

### 개선안

- OCID + Equipment fetch를 **단일 API call**로 병합 불가 (Nexon API 스펙상 분리)
- DB write를 **2-3개 트랜잭션으로 묶기**: (snapshot 관련 1회) + (계산 결과 1회) + (view projection 1회)
- AdvisoryLock follower polling interval 100ms → 50ms 단축

---

## 3. `PgmqWorker:ProcessMessage:expectation_calc_high` (총 3,910s)

| count | avg | p50 | p95 | p99 | max |
|-------|-----|-----|-----|-----|-----|
| 14,733 | 265ms | 245ms | 391ms | 495ms | 987ms |

### 대표 slow event (987ms)

```log
03:13:17.517 [expectation_calc_high-worker] Slow task detected: PgmqWorker:ProcessMessage:expectation_calc_high:51463 (987ms)
03:13:17.521 [expectation_calc_high-worker] Dispatched to consolidated external API pipeline
03:13:17.524 [expectation_calc_high-worker] Slow task detected: ... (482ms)
```

expectation_calc_high worker가 ExternalApiWorker로 dispatch만 하는 구조. 265ms avg의 대부분이 **DB write (job 생성 + pgmq send + archive)**.

### 소스코드: `ExpectationCalcWorker.kt`

```kotlin
// expectation_calc_high은 계산 후 ExternalApiWorker에 dispatch
override fun process(message: PgmqMessage<ExpectationCalcPayload>): Boolean {
    val job = jobService.createJob(ocid, userIgn, presetNo)    // DB INSERT
    jobService.dispatchToExternalApi(job.jobId, userIgn, ...)   // PGMQ send
    return true  // → archive() 호출됨                          // DB DELETE+INSERT
}
```

### 개선안

- ExternalApiWorker가 inline으로 계산하므로 이 큐의 역할이 점차 축소됨
- dispatch + archive를 **배치 처리**로 묶으면 처리량 향상

---

## 4. `TimeoutScanner:Scan:stale_jobs` (총 2,357s)

| count | avg | p50 | p95 | p99 | max |
|-------|-----|-----|-----|-----|-----|
| 54 | 43,643ms | 1,954ms | 238,452ms | 261,398ms | 281,029ms |

### 대표 slow event (281,029ms = **4분 41초**)

```log
# 30초 window 상관관계에서 항상 TimeoutScanner와 OCID_RESOLVE_TIMEOUT가 동시 발생:
04:34:00  slow_count=1192  signals: TimeoutScanner=279, OCID_RESOLVE_TIMEOUT=277
04:36:00  slow_count=1159  signals: TimeoutScanner=426, OCID_RESOLVE_TIMEOUT=426
```

Scanner가 돌 때마다 **DB 경합이 폭증**하고, 그 결과 PgmqClient:Read/Archive도 같이 느려지는 cascading 구조.

### 소스코드: `CalculationJobTimeoutScanner.kt` (fix 전)

```kotlin
@Scheduled(fixedDelayString = "\${job.scanner.timeout-interval-ms:30000}")
fun scanStaleJobs() {
    executor.executeVoid({
        val staleOcidResolving = jobPort.findStaleJobs(OCID_RESOLVING, 30)  // 30초 → 너무 짧음
        for (job in staleOcidResolving) {
            // N+1: job마다 findJobById + incrementRetry + eventAppender.append
            jobService.handleOcidFailure(job.jobId, "OCID_RESOLVE_TIMEOUT", ...)
        }
        // ... API_REQUESTED, RETRYING 동일 패턴
    }, context)
}
```

`handleOcidFailure` 내부:
```kotlin
fun handleOcidFailure(jobId: UUID, errorCode: String, errorMessage: String) {
    val job = jobPort.findJobById(jobId) ?: return              // 개별 SELECT
    val retried = jobPort.incrementRetryForOcid(jobId, errorCode)  // UPDATE
    if (retried) {
        eventAppender.append(ocidResolveTopic, ...)             // legacy topic에 INSERT
    }
}
```

**문제 3가지:**
1. **N+1**: stale job마다 개별 SELECT + UPDATE + INSERT
2. **CAS 없음**: 같은 job에 대해 중복 retry dispatch
3. **Legacy topic**: `ocidResolveTopic`은 ExternalApiWorker가 소비하지 않는 별도 queue → 중복 메시지 누적

### 개선안 (PR #781에서 적용 완료)

```kotlin
// Fix 1: 통합 queue로 라우팅 + CAS 체크
val current = jobPort.findJobById(job.jobId)
if (current?.status == OCID_RESOLVING) {
    jobService.retryExternalApiJob(job.jobId)  // → external_api_queue로 dispatch
}

// Fix 2: batch limit + threshold 상향
.take(maxBatchSize)   // scan당 최대 20건
findStaleJobs(OCID_RESOLVING, 120)  // 30s → 120s
```

**추가 개선 필요:**
- `findJobById` N+1 → `findJobsByIds` IN 쿼리로 1회 조회
- `incrementRetry`에 WHERE status = 'OCID_RESOLVING' CAS 조건 추가

---

## 5. `DlqReplayWorker:Replay` (총 119s)

| count | avg | p50 | p95 | p99 | max |
|-------|-----|-----|-----|-----|-----|
| 5 | 23,807ms | 5,150ms | 70,070ms | 70,070ms | 70,070ms |

### 소스코드: `DlqReplayWorker.kt:71-84`

```kotlin
private fun doReplay() {
    for (queueName in QUEUE_NAMES) {  // 6개 큐 순차 처리
        discoverAndTrack(queueName)       // archive 테이블 SELECT
        totalReplayed += replayEligible(queueName)  // dlq_replay_meta SELECT + 개별 재발행
        totalPermanent += alertPermanentFailures(queueName)  // dlq_replay_meta SELECT
    }
}
```

각 큐당 3회 DB 쿼리 (discover + findReplayCandidates + alertPermanentFailures) × 6개 큐 = **최소 18회 DB 왕복**을 순차 실행.

### 개선안

- 큐별 스캔을 **coroutine 병렬화** (6개 큐 동시 처리)
- `replaySingleMessage`를 **배치 send**로 묶기

---

## 우선순위 정리

| 우선순위 | 작업 | 예상 효과 |
|----------|------|-----------|
| **P0** | TimeoutScanner CAS + N+1 제거 | 2,357s 손실 제거, 중복 queue 메시지 제거, cascading DB 경합 해소 |
| **P0** | Pipeline DB write 7→3 트랜잭션 | external_api 처리량 30~40% 향상 (30,807s 중 DB wait 감소) |
| **P1** | archive 배치화 | archive 98s 손실 제거 + ProcessMessage 지연 감소 |
| **P2** | DlqReplayWorker 병렬화 | 119s → ~20s (6x 개선) |
| **P3** | AdvisoryLock follower poll interval 단축 | lock 대기 시간 100ms→50ms |

TimeoutScanner가 중복 메시지를 queue에 쏟아내면서 external_api_queue 처리 부하를 증폭시키는 **cascading 구조**가 핵심. P0 둘 다 잡으면 slow event 전체의 **70% 이상**이 해소됨.
