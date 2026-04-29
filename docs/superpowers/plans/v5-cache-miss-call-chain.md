# V5 Cache-Miss API 호출 체인 전체 코드

`GET /api/v5/characters/진격캐넌/expectation` 호출 시 캐시 미스 케이스의 전체 로직 흐름.

---

## Phase A: 동기 요청 (HTTP 202 반환까지)

### 1. GameCharacterControllerV5.getExpectationV5

```kotlin
// module-web/src/main/kotlin/maple/expectation/web/controller/v5/GameCharacterControllerV5.kt

@GetMapping("/{userIgn}/expectation")
@PreAuthorize("permitAll()")
fun getExpectationV5(
    @PathVariable @NotBlank @ValidIgn userIgn: String,
    @RequestParam(defaultValue = "1") @Min(1) @Max(3) presetNo: Int = 1,
): CompletableFuture<ResponseEntity<*>> {
    val normalizedIgn = userIgn.trim()
    log.debug("[V5] Query expectation for: {}", maskIgn(normalizedIgn))
    return CompletableFuture.supplyAsync({ processPostgreSQLCacheFirstLookup(normalizedIgn, presetNo) }, computeExecutor)
}
```

### 2. GameCharacterControllerV5.processPostgreSQLCacheFirstLookup

```kotlin
// module-web/src/main/kotlin/maple/expectation/web/controller/v5/GameCharacterControllerV5.kt

private fun processPostgreSQLCacheFirstLookup(userIgn: String, presetNo: Int): ResponseEntity<*> {
    val context = TaskContext.of("V5Query", "CacheFirstLookup", userIgn)

    // 1. Query Side: Check PostgreSQL first via Port
    val cachedResult: Optional<EquipmentExpectationResponseV5> = executorPort.executeOrDefault(
        {
            queryPort.findByUserIgn(userIgn)
                .filter { view -> view.presets?.any { it.presetNo == presetNo } ?: false }
                .map { CharacterViewMapper.toResponseDto(it) }
                .orElse(Optional.empty())
        },
        Optional.empty(),
        context,
    )

    // 2. HIT: Return immediately (1-10ms)
    if (cachedResult.isPresent) {
        log.debug("[V5] PostgreSQL HIT: {} (presetNo={})", maskIgn(userIgn), presetNo)
        return ResponseEntity.ok(cachedResult.get())
    }

    // 3. MISS: Queue to Command Side via Port
    return queueCalculationTask(userIgn, false, presetNo, context)
}
```

### 3. CharacterViewQueryPort.findByUserIgn (interface)

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/inbound/CharacterViewQueryPort.kt

interface CharacterViewQueryPort {
    fun findByUserIgn(userIgn: String): Optional<CharacterView>

    fun upsertFromCalculation(
        userIgn: String,
        messageId: String?,
        characterOcid: String?,
        characterClass: String?,
        characterLevel: Int?,
        totalExpectedCost: Long,
        maxPresetNo: Int,
        presetNo: Int,
        presetsJson: String,
    )
}
```

### 4. CharacterViewQueryPortAdapter.findByUserIgn

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryPortAdapter.kt

override fun findByUserIgn(userIgn: String): Optional<CharacterView> {
    val entity = queryService.findByUserIgn(userIgn)
    return if (entity != null) {
        Optional.of(CharacterViewEntityAdapter(entity))
    } else {
        Optional.empty()
    }
}
```

### 5. CharacterViewQueryServicePostgres.findByUserIgn

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryServicePostgres.kt

@Transactional(value = "transactionManager", readOnly = true)
fun findByUserIgn(userIgn: String): CharacterValuationViewEntity? = findByUserIgnEntity(userIgn)

private fun findByUserIgnEntity(userIgn: String): CharacterValuationViewEntity? {
    val context = TaskContext.of("PostgresQuery", "FindByUserIgn", userIgn)

    return executor.executeOrDefault(
        {
            val startNanos = System.nanoTime()
            val result = repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)
            meterRegistry
                .timer("postgres.query.latency", "operation", if (result != null) "hit" else "miss")
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS)
            result
        },
        null,
        context,
    )
}
```

### 6. ExecutorPort.executeOrDefault (interface)

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/inbound/ExecutorPort.kt

interface ExecutorPort {
    fun executeVoid(task: () -> Unit, context: TaskContext)

    fun <T> executeOrDefault(
        task: () -> T,
        defaultValue: T,
        context: TaskContext,
    ): T

    fun executeVoidJava(task: Runnable, context: TaskContext)

    fun <T> executeOrDefaultJava(
        task: ThrowingSupplier<T>,
        defaultValue: T,
        context: TaskContext,
    ): T

    fun <T> execute(task: () -> T, context: TaskContext): T

    fun <T> executeWithTranslation(
        task: () -> T,
        translator: (Throwable, TaskContext) -> Exception,
        context: TaskContext,
    ): T

    fun interface ThrowingSupplier<T> {
        @Throws(Throwable::class)
        fun get(): T
    }
}
```

### 7. ApplicationExecutionPort.executeOrDefault

```kotlin
// module-app/src/main/kotlin/maple/expectation/application/usecase/ApplicationExecutionPort.kt

override fun <T> executeOrDefault(
    task: () -> T,
    defaultValue: T,
    context: CommonTaskContext,
): T = logicExecutor.executeOrDefault(
    ThrowingSupplier { task() },
    defaultValue,
    toInfraContext(context),
)
```

### 8. GameCharacterControllerV5.queueCalculationTask

```kotlin
// module-web/src/main/kotlin/maple/expectation/web/controller/v5/GameCharacterControllerV5.kt

private fun queueCalculationTask(
    userIgn: String,
    forceRecalculation: Boolean,
    presetNo: Int,
    context: TaskContext,
): ResponseEntity<*> {
    val receipt = executorPort.executeOrDefault(
        { queuePort.offerHighPriorityWithReceipt(userIgn, forceRecalculation, presetNo) },
        TaskReceipt.rejected(userIgn),
        context,
    )

    return if (receipt.queued) {
        log.info("[V5] PostgreSQL MISS, queued calculation: {} (taskId={})", maskIgn(userIgn), receipt.taskId)
        ResponseEntity.accepted()
            .header("X-Task-Id", receipt.taskId)
            .build<Unit>()
    } else {
        log.warn("[V5] Queue full, rejecting: {}", maskIgn(userIgn))
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse.from(CommonErrorCode.SERVICE_UNAVAILABLE))
    }
}
```

### 9. CalculationQueuePort.offerHighPriorityWithReceipt (interface)

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/inbound/CalculationQueuePort.kt

interface CalculationQueuePort {
    fun offerHighPriorityWithReceipt(userIgn: String, forceRecalculation: Boolean, presetNo: Int = 1): TaskReceipt
}
```

### 10. CalculationQueuePortAdapter.offerHighPriorityWithReceipt

```java
// module-app/src/main/java/maple/expectation/application/usecase/CalculationQueuePortAdapter.java

@Override
public TaskReceipt offerHighPriorityWithReceipt(
    String userIgn, boolean forceRecalculation, int presetNo) {
  ExpectationCalculationTask task =
      ExpectationCalculationTask.highPriority(userIgn, forceRecalculation, presetNo);
  return queue.offerWithReceipt(task);
}
```

### 11. ExpectationCalculationQueue.offerWithReceipt

```java
// module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java

@Transactional(value = "transactionManager", propagation = Propagation.REQUIRES_NEW)
public TaskReceipt offerWithReceipt(ExpectationCalculationTask task) {
  TaskContext context = TaskContext.of("Queue", "OfferWithReceipt", task.getUserIgn());
  return executor.executeOrDefault(
      () -> enqueue(task), TaskReceipt.rejected(task.getUserIgn()), context);
}
```

### 12. ExpectationCalculationQueue.enqueue

```java
// module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java

private TaskReceipt enqueue(ExpectationCalculationTask task) {
  CalculationJob job = jobPort.createJob(null, task.getUserIgn(), task.getPresetNo());

  if (job.getStatus() == CalculationJobStatus.REQUESTED) {
    boolean transitioned =
        jobPort.transitionStatus(
            job.getJobId(), CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING);
    if (transitioned) {
      pgmqPort.send(
          QueueNames.EXTERNAL_API,
          new ExternalApiJobPayload(
              job.getJobId().toString(), task.getUserIgn(), task.getPresetNo()));
      log.debug(
          "[Queue] Job dispatched: jobId={}, userIgn={}, presetNo={}",
          job.getJobId(),
          task.getUserIgn(),
          task.getPresetNo());
    }
  } else {
    log.debug(
        "[Queue] Existing active job returned: jobId={}, status={}, userIgn={}",
        job.getJobId(),
        job.getStatus(),
        task.getUserIgn());
  }

  return new TaskReceipt(job.getJobId().toString(), task.getUserIgn(), true);
}
```

### 13. CalculationJobPort.createJob (interface)

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt

interface CalculationJobPort {
    fun createJob(ocid: String?, userIgn: String, presetNo: Int): CalculationJob
    fun findJobById(jobId: UUID): CalculationJob?
    fun transitionStatus(jobId: UUID, from: CalculationJobStatus, to: CalculationJobStatus): Boolean
    fun markSnapshotReady(jobId: UUID, snapshotId: UUID, from: CalculationJobStatus): Boolean
    fun markFailed(jobId: UUID, errorCode: String, errorMessage: String): Boolean
    fun incrementRetry(jobId: UUID, errorCode: String): Boolean
    fun incrementRetryForOcid(jobId: UUID, errorCode: String): Boolean
    fun retryCalculation(jobId: UUID, errorCode: String, nextRetryAt: Instant): Boolean
    fun lockForProcessing(jobId: UUID, workerId: String, from: CalculationJobStatus): Boolean
    fun unlock(jobId: UUID): Boolean
    fun findStaleJobs(status: CalculationJobStatus, olderThanSeconds: Long): List<CalculationJob>
    fun findJobsByIds(ids: List<UUID>): List<CalculationJob>
    fun findActiveJobByUserIgn(userIgn: String, presetNo: Int): CalculationJob?
    fun resolveOcidAndTransition(jobId: UUID, ocid: String): Boolean
    fun findCompletedJobsMissingOutboxEvents(limit: Int): List<UUID>
}
```

### 14. CalculationJobPortAdapter.createJob

```kotlin
// module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt

override fun createJob(ocid: String?, userIgn: String, presetNo: Int): CalculationJob {
    val existing = jobRepository.findActiveByUserIgnAndPreset(userIgn, presetNo)
    if (existing != null) {
        if (existing.status == CalculationJobStatus.CALCULATING.name) {
            jobRepository.markFailed(existing.jobId, "SUPERSEDED", "Superseded by new calculation request")
        } else {
            return existing.toDomain()
        }
    }

    return try {
        val entity = CalculationJobEntity(
            ocid = ocid,
            userIgn = userIgn,
            presetNo = presetNo,
        )
        jobRepository.save(entity).toDomain()
    } catch (ex: DataIntegrityViolationException) {
        log.info("[createJob] Constraint violation on dedup index, returning existing job: userIgn={}, presetNo={}", userIgn, presetNo)
        jobRepository.findActiveByUserIgnAndPreset(userIgn, presetNo)?.toDomain()
            ?: throw ex
    }
}
```

### 15. CalculationJobPortAdapter.transitionStatus

```kotlin
// module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt

override fun transitionStatus(jobId: UUID, from: CalculationJobStatus, to: CalculationJobStatus): Boolean =
    jobRepository.transitionStatus(jobId, from.name, to.name) > 0
```

### 16. PgmqPort.send (interface)

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/PgmqPort.kt

interface PgmqPort {
    fun send(queueName: String, message: Any): Long
    fun queueLength(queueName: String): Long
    fun findActiveMessageIdByUserIgn(queueName: String, userIgn: String): Long?
    fun sendIfAbsent(queueName: String, userIgn: String, payload: Any): Long
}
```

### 17. ExternalApiJobPayload

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/ExternalApiJobPayload.kt

data class ExternalApiJobPayload(
    val jobId: String,
    val userIgn: String,
    val presetNo: Int,
)
```

**→ HTTP 202 Accepted + X-Task-Id 헤더 반환**

---

## Phase B: 비동기 파이프라인 (PGMQ Worker 처리)

### 18. PgmqWorker.processMessages

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt

@Scheduled(fixedDelayString = "\${pgmq.worker.common.polling-interval-ms:300}")
fun processMessages() {
    if (!lifecycleWrapper.beforeTask()) return
    if (!workerSettings.enabled) {
        lifecycleWrapper.afterTask()
        return
    }

    val context = TaskContext.of("PgmqWorker", "ProcessBatch", queueName)

    executor.executeWithFinally(
        task = {
            // Phase A: Flush accumulated messages if time window expired
            if (sequentialBatchMs > 0 && supportsTwoPhase && accumulationBuffer.shouldFlush()) {
                flushSequentialBatch()
            }

            // Phase B: Read new messages
            val permits = inflightPermits.drainPermits()
            if (permits <= 0) return@executeWithFinally

            val batchSize = minOf(
                workerSettings.batchSize ?: config.common.batchSize,
                permits,
            )
            val visibilityTimeout = config.common.visibilityTimeoutSec

            val messages = pgmqClient.read(queueName, payloadClass, batchSize, visibilityTimeout)

            if (pollCounter.incrementAndGet() % 20 == 0) {
                metrics.updateQueueDepth(pgmqClient.queueLength(queueName))
            }

            if (messages.isEmpty()) {
                inflightPermits.release(permits)
                return@executeWithFinally
            }

            val unused = permits - messages.size
            if (unused > 0) inflightPermits.release(unused)

            log.debug("[{}] Processing {} messages", queueName, messages.size)

            messages.forEach { message ->
                metrics.inflightIncrement()
                metrics.recordWaitDuration(message.enqueuedAt)
            }

            // Phase C: Route to processing mode
            if (sequentialBatchMs > 0 && supportsTwoPhase) {
                accumulationBuffer.addAll(messages)
                if (accumulationBuffer.shouldFlush()) {
                    flushSequentialBatch()
                }
            } else if (supportsTwoPhase) {
                if (pipelineBuffer.isFull()) {
                    log.warn("[{}] Pipeline buffer full ({}), draining before poll", queueName, pipelineBuffer.size())
                    drainMicroBatch()
                }
                processBatchPipelined(messages)
            } else {
                processBatchSinglePhase(messages)
            }
        },
        finallyBlock = { lifecycleWrapper.afterTask() },
        context = context,
    )
}
```

### 19. PgmqWorker.processBatchSinglePhase

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt

private fun processBatchSinglePhase(messages: List<PgmqMessage<T>>) {
    val archiveIds = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    val futures = messages.map { message ->
        CompletableFuture.supplyAsync({
            val needsArchive = processSingleMessage(message)
            if (needsArchive) archiveIds.add(message.messageId)
            needsArchive
        }, workerPool)
    }
    pendingBatchFuture = CompletableFuture.allOf(*futures.toTypedArray())
        .exceptionally { ex ->
            log.warn("[{}] Batch completion error: {}", queueName, ex.message)
            null
        }
        .thenAccept {
            if (archiveIds.isNotEmpty()) {
                executor.executeOrDefault(
                    {
                        val archived = pgmqClient.archiveBatch(queueName, archiveIds.toList())
                        log.debug("[{}] Batch archived {}/{} messages", queueName, archived, archiveIds.size)
                    },
                    Unit,
                    TaskContext.of("PgmqWorker", "BatchArchive", queueName),
                )
            }
            log.debug("[{}] Batch of {} messages completed", queueName, messages.size)
        }
}
```

### 20. PgmqWorker.processSingleMessage

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt

private fun processSingleMessage(message: PgmqMessage<T>): Boolean {
    val maxRetries = workerSettings.maxRetries ?: config.common.maxRetries
    val context = TaskContext.of("PgmqWorker", "ProcessMessage", "$queueName:${message.messageId}")

    if (message.readCount > 1) {
        metrics.retry.increment()
    }

    return executor.executeWithFinally(
        task = {
            metrics.concurrentIncrement()
            val success = executor.executeOrDefault(
                { process(message) },
                false,
                context,
            )

            when {
                success -> {
                    metrics.success.increment()
                }
                message.isRetryable(maxRetries) -> {
                    onProcessingFailed(message)
                    metrics.failure.increment()
                    log.warn("[{}] Message will be retried: msgId={}, readCount={}", queueName, message.messageId, message.readCount)
                }
                else -> {
                    metrics.failure.increment()
                    metrics.dlq.increment()
                    log.error("[{}] Max retries exceeded: msgId={}, readCount={}", queueName, message.messageId, message.readCount)
                }
            }

            success
        },
        finallyBlock = {
            metrics.concurrentDecrement()
            metrics.inflightDecrement()
            inflightPermits.release()
        },
        context = context,
    )
}
```

### 21. ExternalApiWorker.process

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt

override fun process(message: PgmqMessage<ExternalApiJobPayload>): Boolean {
    val payload = message.payload
    val jobId = UUID.fromString(payload.jobId)
    val context = TaskContext.of("ExternalApiWorker", "Pipeline", payload.userIgn)

    return executor.executeOrCatch(
        {
            processPipeline(payload)
            true
        },
        { e ->
            log.error("[jobId={}] Pipeline failed: {}", jobId, e.message)
            handleFailure(jobId, e)
            false
        },
        context,
    )
}
```

### 22. ExternalApiWorker.processPipeline (핵심 파이프라인)

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt

private fun processPipeline(payload: ExternalApiJobPayload) {
    val jobId = UUID.fromString(payload.jobId)

    // Early exit: skip expensive API calls if job already completed/processing
    val existingJob = jobPort.findJobById(jobId)
    if (existingJob != null && existingJob.status != CalculationJobStatus.OCID_RESOLVING && existingJob.status != CalculationJobStatus.REQUESTED) {
        log.debug("[jobId={}] Skipping — already in state {}", jobId, existingJob.status)
        return
    }

    // Step 1: Resolve OCID (Nexon API ~200ms)
    val ocid = resolveOcid(jobId, payload.userIgn)

    // Step 2: Fetch equipment data (Nexon API ~300ms)
    val equipmentResponse = equipmentFetchProvider.fetchWithCache(ocid)
    val snapshotData = objectMapper.writeValueAsBytes(equipmentResponse)

    // Step 3: Save snapshot + CalculationInput
    val objectKey = generateObjectKey(jobId)
    val snapshotId = UUID.randomUUID()
    val snapshot = CalculationSnapshot(
        snapshotId = snapshotId,
        jobId = jobId,
        objectKey = objectKey,
        storageType = "LOCAL",
        characterId = ocid,
        presetNo = payload.presetNo,
        expiresAt = Instant.now().plusSeconds(86400),
    )
    val putResult = snapshotStore.put(snapshot, snapshotData)

    val inputItems = (equipmentResponse.itemEquipment ?: emptyList()).map { item ->
        val itemMap = objectMapper.convertValue(item, Map::class.java) as Map<*, *>
        converter.convertItem(itemMap)
    }
    val calcInput = CalculationInput(
        jobId = jobId.toString(),
        userIgn = payload.userIgn,
        characterClass = equipmentResponse.characterClass ?: "",
        presetNo = payload.presetNo,
        items = inputItems,
    )
    if (calculationInputPort.findByJobId(jobId) == null) {
        calculationInputPort.save(calcInput)
    }

    val snapshotEntity = CalculationSnapshotEntity(
        snapshotId = snapshotId,
        jobId = jobId,
        objectKey = objectKey,
        storageType = "LOCAL",
        characterId = ocid,
        presetNo = payload.presetNo,
        compressedSize = putResult.compressedSize,
        originalSize = snapshotData.size.toLong(),
        hash = putResult.hash,
        expiresAt = snapshot.expiresAt,
    )
    jobService.saveInputSnapshotAndMarkReady(snapshotEntity, jobId, snapshotId)

    // Step 4: Calculate (pure CPU, ~ms) + complete in one transaction
    val calcResult = pureCalculationPort.calculate(calcInput)
    val resultJson = objectMapper.writeValueAsString(calcResult)

    executionService.startAndCompleteCalculation(
        jobId = jobId,
        workerId = "ExternalApiWorker",
        resultJson = resultJson,
        characterClass = calcInput.characterClass,
        presetNo = payload.presetNo,
        characterId = ocid,
    )

    // Step 5: View projection — virtual thread fire-and-forget (outbox backstop guarantees eventual consistency)
    Thread.ofVirtual().name("view-projection-$jobId").start {
        try {
            val presetsJson = objectMapper.writeValueAsString(calcResult.presets)
            viewQueryPort.upsertFromCalculation(
                userIgn = payload.userIgn,
                messageId = jobId.toString(),
                characterOcid = ocid,
                characterClass = calcInput.characterClass,
                characterLevel = null,
                totalExpectedCost = calcResult.totalExpectedCost.toLong(),
                maxPresetNo = calcResult.maxPresetNo,
                presetNo = payload.presetNo,
                presetsJson = presetsJson,
            )
        } catch (e: Exception) {
            log.warn("[jobId={}] View projection failed (outbox backstop will retry): {}", jobId, e.message)
        }
    }

    log.info("[jobId={}] Pipeline completed", jobId)
}
```

### 23. ExternalApiWorker.resolveOcid

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt

private fun resolveOcid(jobId: UUID, userIgn: String): String {
    val cached = ocidPort.resolveOcid(userIgn)
    if (cached != null) {
        jobService.resolveOcidInPlace(jobId, cached)
        return cached
    }

    val ocidResponse = nexonApiClient.getOcidByCharacterName(userIgn)
        .handle { result, ex ->
            if (ex != null) {
                log.warn("[jobId={}] OCID resolve failed: {}", jobId, ex.message)
                null
            } else {
                result
            }
        }
        .join()

    if (ocidResponse == null || ocidResponse.ocid.isBlank()) {
        throw IllegalStateException("OCID resolve returned empty for $userIgn")
    }

    val ocid = ocidResponse.ocid
    jobService.resolveOcidInPlace(jobId, ocid)
    return ocid
}
```

### 24. CharacterOcidPort.resolveOcid (interface)

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/CharacterOcidPort.kt

interface CharacterOcidPort {
    fun resolveOcid(userIgn: String): String?

    fun resolveOcids(userIgns: Set<String>): Map<String, String>

    fun resolveAllOcids(): Map<String, String>

    fun resolveOcidsByFingerprint(fingerprint: String): Set<String>

    fun updateFingerprint(ocid: String, fingerprint: String, accountId: String): Int
}
```

### 25. CalculationJobService.resolveOcidInPlace

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt

@Transactional
fun resolveOcidInPlace(jobId: UUID, ocid: String): Boolean = jobPort.resolveOcidAndTransition(jobId, ocid)
```

### 26. CalculationJobPortAdapter.resolveOcidAndTransition

```kotlin
// module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt

override fun resolveOcidAndTransition(jobId: UUID, ocid: String): Boolean =
    jobRepository.resolveOcidAndTransition(jobId, ocid) > 0
```

### 27. EquipmentFetchProvider.fetchWithCache

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/provider/EquipmentFetchProvider.kt

@NexonDataCache
@Cacheable(value = ["equipment"], key = "#ocid", unless = "#root == null")
fun fetchWithCache(ocid: String): EquipmentResponse {
    val start = System.currentTimeMillis()
    val response = nexonApiClient
        .getItemDataByOcid(ocid)
        .orTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .join()
    val elapsed = System.currentTimeMillis() - start
    if (elapsed > 100) {
        logger.warn("[EquipmentProvider] Slow API fetch: ocid={} took {}ms", ocid, elapsed)
    }
    return response
}
```

### 28. SnapshotObjectStore.put (interface)

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/SnapshotObjectStore.kt

interface SnapshotObjectStore {
    fun put(snapshot: CalculationSnapshot, data: ByteArray): SnapshotObjectStoreResult
    fun get(objectKey: String): ByteArray
    fun delete(objectKey: String)
}
```

### 29. CalculationInputPort.save (interface)

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationInputPort.kt

interface CalculationInputPort {
    fun save(input: CalculationInput): CalculationInput
    fun findByJobId(jobId: UUID): CalculationInput?
}
```

### 30. CalculationJobService.saveInputSnapshotAndMarkReady

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt

@Transactional
fun saveInputSnapshotAndMarkReady(
    snapshotEntity: CalculationSnapshotEntity,
    jobId: UUID,
    snapshotId: UUID,
): Boolean {
    snapshotRepository.save(snapshotEntity)
    return jobPort.markSnapshotReady(jobId, snapshotId, CalculationJobStatus.API_REQUESTED)
}
```

### 31. CalculationJobPortAdapter.markSnapshotReady

```kotlin
// module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt

override fun markSnapshotReady(jobId: UUID, snapshotId: UUID, from: CalculationJobStatus): Boolean =
    jobRepository.markSnapshotReady(jobId, snapshotId, from.name) > 0
```

### 32. PureCalculationPort.calculate (interface)

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/PureCalculationPort.kt

interface PureCalculationPort {
    fun calculate(input: CalculationInput): EquipmentExpectationResponseV4
}
```

### 33. PureCalculationAdapter.calculate

```kotlin
// module-app/src/main/kotlin/maple/expectation/application/adapter/PureCalculationAdapter.kt

@Component
class PureCalculationAdapter(
    private val calculator: PureExpectationCalculator,
) : PureCalculationPort {
    override fun calculate(input: CalculationInput): EquipmentExpectationResponseV4 =
        calculator.calculate(input)
}
```

### 34. PureExpectationCalculator.calculate

```kotlin
// module-app/src/main/kotlin/maple/expectation/application/service/expectation/PureExpectationCalculator.kt

fun calculate(input: CalculationInput): EquipmentExpectationResponseV4 {
    val cubeInputs = input.items.map { EquipmentItemConverter.toCubeInput(it) }

    val future = presetHelper.calculatePresetAsync(
        cubeInputs,
        input.presetNo,
        input.characterClass,
    )
    val preset = future.get(30, TimeUnit.SECONDS)

    return EquipmentExpectationResponseV4(
        userIgn = input.userIgn,
        calculatedAt = LocalDateTime.now(),
        fromCache = false,
        totalExpectedCost = preset.totalExpectedCost,
        totalCostText = preset.totalCostText,
        totalCostBreakdown = preset.costBreakdown,
        maxPresetNo = input.presetNo,
        presets = listOf(preset),
    )
}
```

### 35. CalculationExecutionService.startAndCompleteCalculation

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt

@Transactional
fun startAndCompleteCalculation(
    jobId: UUID,
    workerId: String,
    resultJson: String,
    characterClass: String,
    presetNo: Int,
    characterId: String,
): Boolean {
    val locked = jobPort.lockForProcessing(jobId, workerId, CalculationJobStatus.SNAPSHOT_READY)
    if (!locked) return false
    jobPort.transitionStatus(jobId, CalculationJobStatus.SNAPSHOT_READY, CalculationJobStatus.CALCULATING)

    val completed = jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED)
    if (!completed) return false

    val gzipData = gzipCompress(resultJson.toByteArray())
    val hash = sha256Hex(resultJson.toByteArray())

    resultPort.save(
        CalculationResultData(
            resultId = UUID.randomUUID(),
            jobId = jobId,
            characterClass = characterClass,
            presetNo = presetNo,
            schemaVersion = 1,
            contentType = "application/json",
            contentEncoding = "gzip",
            responseBody = gzipData,
            originalSize = resultJson.toByteArray().size,
            compressedSize = gzipData.size,
            hash = hash,
            status = "SUCCESS",
        ),
    )

    val eventPayload = objectMapper.writeValueAsString(
        mapOf(
            "jobId" to jobId.toString(),
            "characterId" to characterId,
            "presetNo" to presetNo,
            "contentEncoding" to "gzip",
            "schemaVersion" to 1,
        ),
    )
    outboxPort.insertIfAbsent("CALCULATION_COMPLETED", jobId, eventPayload)

    jobPort.unlock(jobId)
    log.info("[jobId={}] Calculation completed with result saved", jobId)
    return true
}
```

### 36. CalculationResultPort.save (interface)

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationResultPort.kt

data class CalculationResultData(
    val resultId: UUID,
    val jobId: UUID,
    val characterClass: String?,
    val presetNo: Int,
    val schemaVersion: Int,
    val contentType: String,
    val contentEncoding: String,
    val responseBody: ByteArray,
    val originalSize: Int,
    val compressedSize: Int,
    val hash: String,
    val status: String,
)

interface CalculationResultPort {
    fun save(result: CalculationResultData): CalculationResultData
    fun findByJobId(jobId: UUID): CalculationResultData?
    fun existsByJobId(jobId: UUID): Boolean
}
```

### 37. OutboxEventPort.insertIfAbsent (interface)

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/OutboxEventPort.kt

interface OutboxEventPort {
    fun insertIfAbsent(eventType: String, jobId: UUID, payload: String?): Boolean
    fun findUnpublished(limit: Int): List<OutboxEvent>
    fun markPublished(eventId: UUID)
    fun incrementPublishAttempts(eventId: UUID)
}
```

### 38. CharacterViewQueryPortAdapter.upsertFromCalculation

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryPortAdapter.kt

override fun upsertFromCalculation(
    userIgn: String,
    messageId: String?,
    characterOcid: String?,
    characterClass: String?,
    characterLevel: Int?,
    totalExpectedCost: Long,
    maxPresetNo: Int,
    presetNo: Int,
    presetsJson: String,
) {
    queryService.upsertFromCalculation(
        userIgn, messageId, characterOcid, characterClass, characterLevel,
        totalExpectedCost, maxPresetNo, presetNo, presetsJson,
    )
}
```

### 39. CharacterViewQueryServicePostgres.upsertFromCalculation

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryServicePostgres.kt

fun upsertFromCalculation(
    userIgn: String,
    messageId: String?,
    characterOcid: String?,
    characterClass: String?,
    characterLevel: Int?,
    totalExpectedCost: Long,
    maxPresetNo: Int,
    presetNo: Int,
    presetsJson: String,
) {
    val context = TaskContext.of("PostgresQuery", "UpsertFromCalculation", userIgn)
    executor.executeVoid({
        val presets: List<CharacterValuationViewEntity.PresetView>? = executor.executeOrDefault(
            {
                objectMapper.readValue(
                    presetsJson,
                    objectMapper.typeFactory.constructCollectionType(List::class.java, CharacterValuationViewEntity.PresetView::class.java),
                )
            },
            null,
            TaskContext.of("PostgresQuery", "ParsePresets", userIgn),
        )
        val entity = CharacterValuationViewEntity(
            userIgn = userIgn,
            messageId = messageId,
            characterOcid = characterOcid,
            characterClass = characterClass,
            characterLevel = characterLevel,
            totalExpectedCost = totalExpectedCost,
            maxPresetNo = maxPresetNo,
            presetNo = presetNo,
            presets = presets,
            calculatedAt = java.time.Instant.now(),
            fromCache = false,
            version = System.currentTimeMillis(),
        )
        upsert(entity)
    }, context)
}
```

### 40. CharacterViewQueryServicePostgres.upsert

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryServicePostgres.kt

@Transactional("transactionManager")
fun upsert(entity: CharacterValuationViewEntity) {
    val context = TaskContext.of("PostgresQuery", "Upsert", entity.userIgn)
    executor.executeVoid({ performUpsert(entity) }, context)
}

private fun performUpsert(entity: CharacterValuationViewEntity) {
    val existing = findExistingEntity(entity)
    val saved = if (existing != null) {
        updateOrSkipExisting(existing, entity)
    } else {
        insertNew(entity)
    }
    val readModelSource = saved ?: existing
    if (readModelSource != null) {
        saveToReadModel(readModelSource)
    }
}
```

---

## Job 상태 전이 요약

```
REQUESTED → OCID_RESOLVING → API_REQUESTED → SNAPSHOT_READY → CALCULATING → COMPLETED
    ↑            (Step 12)        (Step 30)       (Step 35)      (Step 35)    (Step 35)
```
