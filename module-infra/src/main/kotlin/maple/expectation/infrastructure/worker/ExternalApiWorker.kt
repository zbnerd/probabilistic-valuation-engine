package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.dto.v4.EquipmentItem
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.core.port.out.PureCalculationPort
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.error.exception.CharacterNotFoundException
import maple.expectation.infrastructure.converter.EquipmentResponseToCalculationInputConverter
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.StepTimer
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.infrastructure.job.CalculationExecutionService
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.job.OcidResolutionOrchestrator
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.lifecycle.VirtualThreadExecutorManager
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.pgmq.CalculationRequestedPayload
import maple.expectation.infrastructure.pgmq.ExternalApiJobPayload
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import maple.expectation.infrastructure.provider.EquipmentFetchProvider
import maple.expectation.infrastructure.queue.QueueNames
import maple.expectation.util.ExceptionUtils
import maple.expectation.util.GzipUtils.compress
import maple.expectation.util.HashUtils.sha256Hex
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * Consolidated pipeline worker.
 *
 * When `app.pipeline.consolidated.enabled=true` (default):
 *   External API fetch → calculation input build → pure calculation → result write → outbox insert
 *   All in one worker, CPU work outside transactions, only DB writes inside transactions.
 *
 * When consolidated=false (legacy split pipeline):
 *   External API fetch → snapshot → dispatch to calculation_requested_queue
 */
@Component
@ConditionalOnProperty(name = ["app.worker.external-api.enabled"], havingValue = "true", matchIfMissing = true)
class ExternalApiWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    private val workerConfig: PgmqWorkerConfig,
    meterRegistry: MeterRegistry,
    queueMetrics: WorkerQueueMetrics,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    private val nexonApiClient: NexonApiClient,
    private val equipmentFetchProvider: EquipmentFetchProvider,
    private val snapshotStore: SnapshotObjectStore,
    private val jobService: CalculationJobService,
    private val ocidOrchestrator: OcidResolutionOrchestrator,
    private val executionService: CalculationExecutionService,
    private val objectMapper: ObjectMapper,
    private val converter: EquipmentResponseToCalculationInputConverter,
    private val calculationInputPort: CalculationInputPort,
    private val jobPort: CalculationJobPort,
    private val ocidPort: CharacterOcidPort,
    private val pureCalculationPort: PureCalculationPort,
    @Value("\${app.pipeline.consolidated.enabled:true}") private val consolidatedEnabled: Boolean,
    @Value("\${app.slow-task.step-trace.threshold-ms:500}") private val stepTraceThresholdMs: Long,
) : PgmqWorker<ExternalApiJobPayload>(pgmqClient, executor, workerConfig, meterRegistry, queueMetrics, lifecycleWrapper) {

    private val snapshotExec = VirtualThreadExecutorManager("ExternalApiWorker-snapshot")
    private val apiCallExec = VirtualThreadExecutorManager("ExternalApiWorker-apiCall")

    @PreDestroy
    fun shutdownExecutors() {
        snapshotExec.shutdown()
        apiCallExec.shutdown()
    }

    override val queueName: String = QueueNames.EXTERNAL_API
    override val payloadClass: Class<ExternalApiJobPayload> = ExternalApiJobPayload::class.java
    override val workerSettings: PgmqWorkerConfig.WorkerSettings = workerConfig.externalApi

    override fun process(message: PgmqMessage<ExternalApiJobPayload>): Boolean {
        val payload = message.payload
        val jobId = UUID.fromString(payload.jobId)
        val context = TaskContext.of("ExternalApiWorker", "ProcessMessage", payload.userIgn)

        return executor.executeOrCatch(
            {
                try {
                    pipelineAsync(payload).join()
                } catch (ex: CompletionException) {
                    throw ex.cause ?: ex
                }
                true
            },
            { e ->
                if (isCharacterNotFound(e)) {
                    val errorMsg = (ExceptionUtils.unwrapAsyncException(e)?.message ?: "Character not found").take(200)
                    jobPort.markFailed(jobId, "CHARACTER_NOT_FOUND", errorMsg)
                    log.warn("[jobId={}] Character not found, skipping retry: {}", jobId, errorMsg)
                    true
                } else {
                    log.error("[jobId={}] External API stage failed: {}", jobId, e.message)
                    handleFailure(jobId, e)
                }
            },
            context,
        )
    }

    override fun onProcessingFailed(message: PgmqMessage<ExternalApiJobPayload>) {
        val jobId = UUID.fromString(message.payload.jobId)
        val maxRetries = workerSettings.maxRetries ?: workerConfig.common.maxRetries
        if (message.readCount >= maxRetries) {
            jobPort.markFailed(jobId, "EXTERNAL_API_ERROR", "Max retries exceeded (attempts=${message.readCount})")
            log.error("[jobId={}] External API stage failed permanently after {} attempts", jobId, message.readCount)
        } else {
            log.warn("[jobId={}] External API stage will retry (attempt {})", jobId, message.readCount)
        }
    }

    private fun pipelineAsync(payload: ExternalApiJobPayload): CompletableFuture<Unit> {
        val jobId = UUID.fromString(payload.jobId)
        val timer = StepTimer("ExternalApiWorker:ProcessMessage", stepTraceThresholdMs, tags = mapOf("jobId" to payload.jobId))

        return CompletableFuture.supplyAsync({
            stage("FindJob", jobId.toString()) {
                jobPort.findJobById(jobId)
            }
        }, apiCallExec.executor)
            .thenApply { existingJob ->
                timer.mark("findJob")
                existingJob
            }
            .thenCompose { existingJob ->
                // Terminal states: skip entirely
                if (existingJob != null && (existingJob.status == CalculationJobStatus.COMPLETED || existingJob.status == CalculationJobStatus.FAILED)) {
                    log.debug("[jobId={}] Skipping — terminal state {}", jobId, existingJob.status)
                    return@thenCompose CompletableFuture.completedFuture(Unit)
                }

                // Consolidated retry: SNAPSHOT_READY means API+snapshot already done
                if (consolidatedEnabled && existingJob != null && existingJob.status == CalculationJobStatus.SNAPSHOT_READY) {
                    val characterId = existingJob.ocid
                    if (characterId == null) {
                        log.warn("[jobId={}] No OCID in SNAPSHOT_READY state, cannot retry calculation", jobId)
                        return@thenCompose CompletableFuture.completedFuture(Unit)
                    }
                    val characterClass = stage("LoadCharacterClass", jobId.toString()) {
                        calculationInputPort.findByJobId(jobId)?.characterClass ?: ""
                    }
                    timer.mark("loadCharacterClass")
                    log.info("[jobId={}] Resuming from calculation (SNAPSHOT_READY)", jobId)
                    return@thenCompose CompletableFuture.supplyAsync({
                        runCalculationAndComplete(jobId, payload, characterId, characterClass)
                        timer.mark("runCalculationAndComplete")
                        Unit
                    }, apiCallExec.executor)
                }

                // Not processable
                if (existingJob != null && !existingJob.status.isExternalApiProcessable()) {
                    log.debug("[jobId={}] Skipping — state {}", jobId, existingJob.status)
                    return@thenCompose CompletableFuture.completedFuture(Unit)
                }

                // === Full pipeline: API fetch → snapshot → calculation → result write ===
                resolveOcidAndFetchEquipmentAsync(jobId, payload.userIgn, existingJob?.ocid)
                    .thenApply { equipmentResult ->
                        timer.mark("resolveAndFetch")
                        equipmentResult
                    }
                    .thenCompose { (ocid, equipmentResponse) ->
                        // Step 3: Serialize snapshot (CPU, fast)
                        val snapshotData = stage("SerializeSnapshot", payload.userIgn) {
                            objectMapper.writeValueAsBytes(equipmentResponse)
                        }
                        timer.mark("serializeSnapshot")

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

                        // Step 3.5: Snapshot put — async on snapshotExec (overlaps with input building)
                        val snapshotPutFuture = CompletableFuture.supplyAsync({
                            stage("SnapshotPut", jobId.toString()) {
                                snapshotStore.put(snapshot, snapshotData)
                            }
                        }, snapshotExec.executor)

                        // Step 4: Build input + save (overlaps with snapshot put)
                        val inputItems = convertItems(equipmentResponse)
                        val characterClass = equipmentResponse.characterClass ?: ""
                        val calcInput = CalculationInput(
                            jobId = jobId.toString(),
                            userIgn = payload.userIgn,
                            characterClass = characterClass,
                            presetNo = payload.presetNo,
                            items = inputItems,
                        )
                        stage("SaveCalculationInput", jobId.toString()) {
                            calculationInputPort.saveIfAbsent(calcInput)
                        }
                        timer.mark("buildAndSaveInput")

                        // Step 5+6: Wait for snapshot put → save metadata → (calculation or dispatch)
                        snapshotPutFuture.thenCompose { putResult ->
                            timer.mark("awaitSnapshotPut")

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

                            if (consolidatedEnabled) {
                                stage("SaveSnapshotAndMarkReady", jobId.toString()) {
                                    jobService.saveInputSnapshotAndMarkReady(snapshotEntity, jobId, snapshotId)
                                }
                                timer.mark("saveSnapshotAndMarkReady")

                                // Step 7-10: Inline calculation + result write
                                CompletableFuture.supplyAsync({
                                    runCalculationAndComplete(jobId, payload, ocid, characterClass)
                                    timer.mark("runCalculationAndComplete")
                                    Unit
                                }, apiCallExec.executor)
                            } else {
                                // Legacy: dispatch to calculation_requested_queue
                                stage("DispatchCalculation", jobId.toString()) {
                                    jobService.saveInputSnapshotAndDispatchCalculation(
                                        snapshotEntity = snapshotEntity,
                                        jobId = jobId,
                                        snapshotId = snapshotId,
                                        payload = CalculationRequestedPayload(
                                            jobId = jobId.toString(),
                                            userIgn = payload.userIgn,
                                            presetNo = payload.presetNo,
                                            characterId = ocid,
                                            characterClass = characterClass,
                                        ),
                                    )
                                }
                                timer.mark("dispatchCalculation")
                                CompletableFuture.completedFuture(Unit)
                            }
                        }
                    }
            }
            .whenComplete { _, _ -> timer.close(log) }
            .thenApply { Unit }
    }

    private fun runCalculationAndComplete(
        jobId: UUID,
        payload: ExternalApiJobPayload,
        characterId: String,
        characterClass: String,
    ) {
        val timer = StepTimer("ExternalApiWorker:PureCalculate", stepTraceThresholdMs, tags = mapOf("jobId" to jobId.toString()))
        try {
            // Load input [DB read, no TX — VT]
            val input = stage("LoadInput", jobId.toString()) {
                calculationInputPort.findByJobId(jobId) ?: error("Calculation input missing: $jobId")
            }
            timer.mark("loadInput")

            // CPU section: calculate + serialize + gzip + SHA-256 on Dispatchers.Default
            // Issue #1131: ItemCalculationExecutorConfig "VT rejected due to 3.5x latency regression on CPU-bound work" 원칙 적용.
            val cpu = runBlocking(Dispatchers.Default) {
                val calcResult = stage("PureCalculate", payload.userIgn) {
                    pureCalculationPort.calculate(input)
                }
                timer.mark("pureCalculate")
                val resultBytes = stage("SerializeResult", payload.userIgn) {
                    objectMapper.writeValueAsString(calcResult).toByteArray()
                }
                timer.mark("serializeResult")
                val gzipData = stage("GzipResult", payload.userIgn) {
                    compress(resultBytes)
                }
                timer.mark("gzipResult")
                val hash = stage("HashResult", payload.userIgn) {
                    sha256Hex(resultBytes)
                }
                timer.mark("hashResult")
                CalcCpuResult(calcResult, resultBytes, gzipData, hash)
            }

            // Result write [TX: SNAPSHOT_READY → COMPLETED + result save + outbox insert — VT]
            stage("CompleteCalculation", jobId.toString()) {
                executionService.completeCalculation(
                    jobId = jobId,
                    gzipData = cpu.gzipData,
                    hash = cpu.hash,
                    originalSize = cpu.resultBytes.size,
                    compressedSize = cpu.gzipData.size,
                    characterClass = characterClass,
                    presetNo = payload.presetNo,
                    characterId = characterId,
                    totalExpectedCost = cpu.calcResult.totalExpectedCost.toLong(),
                    maxPresetNo = cpu.calcResult.maxPresetNo,
                    presetsJson = objectMapper.writeValueAsString(cpu.calcResult.presets),
                )
            }
            timer.mark("completeCalculation")
        } finally {
            timer.close(log)
        }
    }

    // Issue #1131: CPU section 의 4 result 를 묶어 caller 로 전달.
    private data class CalcCpuResult(
        val calcResult: PureCalculationResult,
        val resultBytes: ByteArray,
        val gzipData: ByteArray,
        val hash: String,
    )

    private fun convertItems(equipmentResponse: EquipmentResponse): List<EquipmentItem> {
        val items = equipmentResponse.itemEquipment ?: return emptyList()
        return items.map { convertItem(it) }
    }

    private fun convertItem(item: Any): EquipmentItem {
        val itemMap = objectMapper.convertValue(item, Map::class.java) as Map<*, *>
        return converter.convertItem(itemMap)
    }

    /**
     * Resolve OCID and fetch equipment data — async.
     *
     * OCID cache hit: dispatches equipment fetch to apiCallExec (fetchWithCache may block on cache miss).
     * OCID cache miss: chains OCID API → equipment API via thenCompose.
     *
     * No .join() — returns CompletableFuture for chaining.
     */
    private fun resolveOcidAndFetchEquipmentAsync(
        jobId: UUID,
        userIgn: String,
        jobOcid: String?,
    ): CompletableFuture<Pair<String, EquipmentResponse>> {
        val cached = jobOcid ?: ocidPort.resolveOcid(userIgn)
        if (cached != null) {
            ocidOrchestrator.resolveOcidInPlace(jobId, cached)
            return CompletableFuture.supplyAsync({
                Pair(cached, equipmentFetchProvider.fetchWithCache(cached))
            }, apiCallExec.executor)
        }

        return nexonApiClient.getOcidByCharacterName(userIgn)
            .handle { result, ex ->
                if (ex != null) {
                    log.warn("[jobId={}] OCID resolve failed: {}", jobId, ex.message)
                    throw ExceptionUtils.unwrapAs(ex, CharacterNotFoundException::class.java) ?: ex
                }
                result
            }
            .thenApply { response ->
                if (response == null || response.ocid.isBlank()) {
                    throw CharacterNotFoundException(userIgn)
                }
                response.ocid
            }
            .thenApply { ocid ->
                ocidOrchestrator.resolveOcidInPlace(jobId, ocid)
                ocid
            }
            .thenCompose { ocid ->
                CompletableFuture.supplyAsync({
                    log.debug("[VT] API call on virtual thread: isVirtual={}", Thread.currentThread().isVirtual)
                    Pair(ocid, equipmentFetchProvider.fetchWithCache(ocid))
                }, apiCallExec.executor)
            }
            .orTimeout(15, TimeUnit.SECONDS)
    }

    private fun handleFailure(jobId: UUID, e: Throwable): Boolean {
        val job = jobPort.findJobById(jobId) ?: return false
        val errorCode = classifyExternalApiError(e)
        val errorMsg = (e.message ?: "Unknown error").take(200)

        if (job.retryCount >= job.maxRetries) {
            jobPort.markFailed(jobId, errorCode, errorMsg)
            return true
        } else {
            return jobService.retryExternalApiJob(jobId, errorCode)
        }
    }

    private fun <T> stage(name: String, key: String, block: () -> T): T = executor.execute(
        { block() },
        TaskContext.of("ExternalApiWorker", name, key),
    )

    private fun isCharacterNotFound(e: Throwable): Boolean = ExceptionUtils.containsCause(e, CharacterNotFoundException::class.java)

    private fun CalculationJobStatus.isExternalApiProcessable(): Boolean = this == CalculationJobStatus.REQUESTED ||
        this == CalculationJobStatus.OCID_RESOLVING ||
        this == CalculationJobStatus.API_REQUESTED ||
        this == CalculationJobStatus.RETRYING

    private fun classifyExternalApiError(e: Throwable): String {
        val responseException = ExceptionUtils.unwrapAs(e, WebClientResponseException::class.java) ?: return "EXTERNAL_API_ERROR"
        return when (responseException.statusCode.value()) {
            400 -> if (responseException.responseBodyAsString.contains("OPENAPI00004")) {
                "CHARACTER_NOT_FOUND"
            } else {
                "NEXON_BAD_REQUEST"
            }
            401 -> "NEXON_UNAUTHORIZED"
            403 -> "NEXON_FORBIDDEN"
            429 -> "NEXON_RATE_LIMITED"
            in 500..599 -> "NEXON_SERVER_ERROR"
            else -> "EXTERNAL_API_ERROR"
        }
    }

    private fun generateObjectKey(jobId: UUID): String {
        val now = Instant.now()
        val zoned = now.atZone(ZoneOffset.UTC)
        val datePath = "%04d/%02d/%02d".format(zoned.year, zoned.monthValue, zoned.dayOfMonth)
        return "snapshots/$datePath/$jobId.gz"
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExternalApiWorker::class.java)
    }
}
