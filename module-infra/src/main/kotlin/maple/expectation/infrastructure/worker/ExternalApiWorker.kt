package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.dto.v4.EquipmentItem
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.core.port.out.PureCalculationPort
import maple.expectation.core.port.out.QueueNames
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.error.exception.CharacterNotFoundException
import maple.expectation.infrastructure.converter.EquipmentResponseToCalculationInputConverter
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.infrastructure.job.CalculationExecutionService
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.pgmq.ExternalApiJobPayload
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import maple.expectation.infrastructure.provider.EquipmentFetchProvider
import maple.expectation.util.ExceptionUtils
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * Consolidated External API Worker (extends PgmqWorker for parallel processing)
 *
 * Replaces OcidResolveWorker + NexonApiWorker + ApiResponseWorker.
 * Processes messages in parallel on a thread pool (worker-pool-size threads).
 *
 * Pipeline per message (~500ms):
 *   OCID resolve (~200ms) -> Equipment API (~300ms) -> Snapshot save -> Calculate -> Result save
 *
 * Throughput: ~workerPoolSize × 2 messages/sec (vs ~2/sec sequential with PgmqTopicGroup)
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
    private val objectMapper: ObjectMapper,
    private val converter: EquipmentResponseToCalculationInputConverter,
    private val calculationInputPort: CalculationInputPort,
    private val pureCalculationPort: PureCalculationPort,
    private val jobPort: CalculationJobPort,
    private val ocidPort: CharacterOcidPort,
    private val executionService: CalculationExecutionService,
) : PgmqWorker<ExternalApiJobPayload>(pgmqClient, executor, workerConfig, meterRegistry, queueMetrics, lifecycleWrapper) {

    private val snapshotWriter: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    private val apiCallPool: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    @PreDestroy
    fun shutdownExecutors() {
        snapshotWriter.shutdown()
        apiCallPool.shutdown()
        snapshotWriter.awaitTermination(5, TimeUnit.SECONDS)
        apiCallPool.awaitTermination(5, TimeUnit.SECONDS)
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
                processPipeline(payload)
                true
            },
            { e ->
                if (isCharacterNotFound(e)) {
                    val errorMsg = (ExceptionUtils.unwrapAsyncException(e)?.message ?: "Character not found").take(200)
                    jobPort.markFailed(jobId, "CHARACTER_NOT_FOUND", errorMsg)
                    log.warn("[jobId={}] Character not found, skipping retry: {}", jobId, errorMsg)
                    true
                } else {
                    log.error("[jobId={}] Pipeline failed: {}", jobId, e.message)
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
            log.error("[jobId={}] Pipeline failed permanently after {} attempts", jobId, message.readCount)
        } else {
            log.warn("[jobId={}] Pipeline will retry (attempt {})", jobId, message.readCount)
        }
    }

    private fun processPipeline(payload: ExternalApiJobPayload) {
        val jobId = UUID.fromString(payload.jobId)

        // Early exit: skip expensive API calls if job already completed/processing
        val existingJob = jobPort.findJobById(jobId)
        if (existingJob != null && !existingJob.status.isExternalApiProcessable()) {
            log.debug("[jobId={}] Skipping — already in state {}", jobId, existingJob.status)
            return
        }

        // Step 1+2: Resolve OCID → Fetch equipment (thenCompose chain, single block point on cache miss)
        val (ocid, equipmentResponse) = stage("ResolveAndFetch", payload.userIgn) {
            resolveOcidAndFetchEquipment(jobId, payload.userIgn, existingJob?.ocid)
        }

        // Step 3: Submit snapshot write to overlap with calculation
        val snapshotData = stage("SerializeSnapshot", payload.userIgn) {
            objectMapper.writeValueAsBytes(equipmentResponse)
        }
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
        val snapshotFuture = CompletableFuture.supplyAsync({
            stage("SnapshotPut", jobId.toString()) {
                snapshotStore.put(snapshot, snapshotData)
            }
        }, snapshotWriter)

        // Step 4: Build input items + calculate (overlaps with snapshot file I/O)
        val inputItems = stage("BuildCalculationInput", payload.userIgn) {
            convertItems(equipmentResponse)
        }
        val calcInput = CalculationInput(
            jobId = jobId.toString(),
            userIgn = payload.userIgn,
            characterClass = equipmentResponse.characterClass ?: "",
            presetNo = payload.presetNo,
            items = inputItems,
        )
        stage("SaveCalculationInput", jobId.toString()) {
            calculationInputPort.saveIfAbsent(calcInput)
        }

        // Step 5: Calculate (pure CPU) + pre-compute gzip/hash outside transaction
        val calcResult = stage("PureCalculate", payload.userIgn) {
            pureCalculationPort.calculate(calcInput)
        }
        val resultBytes = stage("SerializeResult", payload.userIgn) {
            objectMapper.writeValueAsString(calcResult).toByteArray()
        }
        val gzipData = stage("GzipResult", payload.userIgn) {
            gzipCompress(resultBytes)
        }
        val hash = stage("HashResult", payload.userIgn) {
            sha256Hex(resultBytes)
        }

        // Wait for snapshot write completion before transactional save
        val putResult = stage("AwaitSnapshotPut", jobId.toString()) {
            snapshotFuture.join()
        }

        // Step 6: Save snapshot metadata + result in transaction
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
        stage("SaveSnapshotMetadata", jobId.toString()) {
            jobService.saveInputSnapshotAndMarkReady(snapshotEntity, jobId, snapshotId)
        }

        stage("PersistResult", jobId.toString()) {
            executionService.completeCalculation(
                jobId = jobId,
                gzipData = gzipData,
                hash = hash,
                originalSize = resultBytes.size,
                compressedSize = gzipData.size,
                characterClass = calcInput.characterClass,
                presetNo = payload.presetNo,
                characterId = ocid,
            )
        }

        log.info("[jobId={}] Pipeline completed", jobId)
    }

    private fun convertItems(equipmentResponse: EquipmentResponse): List<EquipmentItem> {
        val items = equipmentResponse.itemEquipment ?: return emptyList()
        if (items.size < PARALLEL_ITEM_CONVERSION_THRESHOLD) {
            return items.map { convertItem(it) }
        }

        return runBlocking(Dispatchers.Default) {
            items.map { item ->
                async(Dispatchers.Default) {
                    convertItem(item)
                }
            }.awaitAll()
        }
    }

    private fun convertItem(item: Any): EquipmentItem {
        val itemMap = objectMapper.convertValue(item, Map::class.java) as Map<*, *>
        return converter.convertItem(itemMap)
    }

    /**
     * Resolve OCID and fetch equipment data.
     *
     * OCID cache hit: synchronous fast path (no API call).
     * OCID cache miss: chains OCID API → equipment API via thenCompose,
     * blocking once at .join() instead of twice.
     */
    private fun resolveOcidAndFetchEquipment(jobId: UUID, userIgn: String, jobOcid: String?): Pair<String, EquipmentResponse> {
        val cached = jobOcid ?: ocidPort.resolveOcid(userIgn)
        if (cached != null) {
            jobService.resolveOcidInPlace(jobId, cached)
            return Pair(cached, equipmentFetchProvider.fetchWithCache(cached))
        }

        return nexonApiClient.getOcidByCharacterName(userIgn)
            .handle { result, ex ->
                if (ex != null) {
                    log.warn("[jobId={}] OCID resolve failed: {}", jobId, ex.message)
                    throw ExceptionUtils.unwrapAs(ex, CharacterNotFoundException::class.java) ?: ex
                } else {
                    result
                }
            }
            .thenApply { response ->
                if (response == null || response.ocid.isBlank()) {
                    throw CharacterNotFoundException(userIgn)
                }
                response.ocid
            }
            .thenApply { ocid ->
                jobService.resolveOcidInPlace(jobId, ocid)
                ocid
            }
            .thenCompose { ocid ->
                CompletableFuture.supplyAsync({
                    log.debug("[VT] API call on virtual thread: isVirtual={}", Thread.currentThread().isVirtual)
                    Pair(ocid, equipmentFetchProvider.fetchWithCache(ocid))
                }, apiCallPool)
            }
            .orTimeout(15, TimeUnit.SECONDS)
            .join()
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

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExternalApiWorker::class.java)
        private const val PARALLEL_ITEM_CONVERSION_THRESHOLD = 8
    }
}
