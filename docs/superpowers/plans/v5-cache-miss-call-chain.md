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

### 3. CharacterViewQueryPortAdapter.findByUserIgn

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

### 6. ApplicationExecutionPort.executeOrDefault

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

private fun toInfraContext(context: CommonTaskContext): InfraTaskContext = InfraTaskContext.of(
    context.component(),
    context.operation(),
    context.dynamicValue(),
)
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

### 9. CalculationQueuePortAdapter.offerHighPriorityWithReceipt

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

### 13. CalculationJobPortAdapter.createJob

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

private fun CalculationJobEntity.toDomain() = CalculationJob(
    jobId = jobId,
    ocid = ocid,
    userIgn = userIgn,
    presetNo = presetNo,
    status = CalculationJobStatus.valueOf(status),
    snapshotId = snapshotId,
    retryCount = retryCount,
    maxRetries = maxRetries,
    nextRetryAt = nextRetryAt,
    lockedBy = lockedBy,
    lockedUntil = lockedUntil,
    lastErrorCode = lastErrorCode,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
)
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

### 16. PgmqPortAdapter.send

```kotlin
// module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/PgmqPortAdapter.kt

override fun send(queueName: String, message: Any): Long = pgmqClient.send(queueName, message)
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

### 24. CharacterOcidAdapter.resolveOcid

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/CharacterOcidAdapter.kt

@Cacheable(value = ["ocidCache"], key = "#userIgn", unless = "#result == null")
override fun resolveOcid(userIgn: String): String? = executor.execute(
    { resolveFromDb(userIgn) },
    TaskContext.of("CharacterOcidAdapter", "ResolveOcid", userIgn),
)

private fun resolveFromDb(userIgn: String): String? {
    val entity = jpaRepository.findByUserIgn(userIgn)
    return entity?.ocid
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

### 28. LocalSnapshotObjectStore.put

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/external/snapshot/LocalSnapshotObjectStore.kt

private val writePermits = Semaphore(10)

override fun put(snapshot: CalculationSnapshot, data: ByteArray): SnapshotObjectStoreResult {
    val context = TaskContext.of("SnapshotStore", "Put", snapshot.objectKey)
    return executor.executeWithFinally({
        writePermits.acquire()
        doPut(snapshot, data)
    }, {
        writePermits.release()
    }, context)
}

private fun doPut(snapshot: CalculationSnapshot, data: ByteArray): SnapshotObjectStoreResult {
    val compressed = gzipCompress(data)
    val hash = sha256(compressed)
    val fullPath = resolveFullPath(snapshot.objectKey)

    fullPath.parent.toFile().mkdirs()

    val tempFile = fullPath.resolveSibling(fullPath.fileName.toString() + ".tmp")
    FileOutputStream(tempFile.toFile()).use { fos ->
        fos.write(compressed)
    }
    java.nio.file.Files.move(tempFile, fullPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

    return SnapshotObjectStoreResult(
        objectKey = snapshot.objectKey,
        compressedSize = compressed.size.toLong(),
        hash = hash,
    )
}

private fun resolveFullPath(objectKey: String): Path {
    val logicalKey = objectKey.removePrefix("/")
    return Paths.get(basePath, logicalKey)
}

private fun gzipCompress(data: ByteArray): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(data) }
    return bos.toByteArray()
}

private fun sha256(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(data).joinToString("") { "%02x".format(it) }
}
```

### 29. CalculationInputPortAdapter.save

```kotlin
// module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationInputPortAdapter.kt

override fun save(input: CalculationInput): CalculationInput {
    val payload = objectMapper.writeValueAsString(input)
    repo.save(
        CalculationSnapshotInputEntity(
            jobId = UUID.fromString(input.jobId),
            schemaVersion = input.schemaVersion,
            payload = payload,
        ),
    )
    return input
}

override fun findByJobId(jobId: UUID): CalculationInput? {
    val entity = repo.findByJobId(jobId) ?: return null
    return objectMapper.readValue(entity.payload, CalculationInput::class.java)
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

### 32. PureCalculationAdapter.calculate

```kotlin
// module-app/src/main/kotlin/maple/expectation/application/adapter/PureCalculationAdapter.kt

override fun calculate(input: CalculationInput): EquipmentExpectationResponseV4 =
    calculator.calculate(input)
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

### 36. CalculationResultPortAdapter.save

```kotlin
// module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationResultPortAdapter.kt

override fun save(result: CalculationResultData): CalculationResultData {
    val existing = repo.findByJobId(result.jobId)
    val entity = if (existing != null && existing.hash == result.hash) {
        existing
    } else {
        repo.save(
            CalculationResultEntity(
                resultId = result.resultId,
                jobId = result.jobId,
                characterClass = result.characterClass,
                presetNo = result.presetNo,
                schemaVersion = result.schemaVersion,
                contentType = result.contentType,
                contentEncoding = result.contentEncoding,
                responseBody = result.responseBody,
                originalSize = result.originalSize,
                compressedSize = result.compressedSize,
                hash = result.hash,
                status = result.status,
            ),
        )
    }
    return CalculationResultData(
        resultId = entity.resultId,
        jobId = entity.jobId,
        characterClass = entity.characterClass,
        presetNo = entity.presetNo,
        schemaVersion = entity.schemaVersion,
        contentType = entity.contentType,
        contentEncoding = entity.contentEncoding,
        responseBody = entity.responseBody,
        originalSize = entity.originalSize,
        compressedSize = entity.compressedSize,
        hash = entity.hash ?: "",
        status = entity.status,
    )
}
```

### 37. OutboxEventPortAdapter.insertIfAbsent

```kotlin
// module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapter.kt

override fun insertIfAbsent(eventType: String, jobId: UUID, payload: String?): Boolean =
    repo.insertIfAbsent(UUID.randomUUID(), eventType, jobId, payload) > 0
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
