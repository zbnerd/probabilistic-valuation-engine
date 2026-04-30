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
import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.core.port.out.PureCalculationPort
import maple.expectation.core.port.out.QueueNames
import maple.expectation.core.port.out.SnapshotObjectStore
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
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

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

    private val snapshotWriter: ExecutorService = Executors.newFixedThreadPool(
        SNAPSHOT_WRITER_POOL_SIZE,
    ) { r -> Thread.ofPlatform().name("snapshot-writer-" + THREAD_COUNTER.getAndIncrement()).daemon(true).unstarted(r) }

    private val apiCallPool: ExecutorService = Executors.newFixedThreadPool(
        API_CALL_POOL_SIZE,
    ) { r -> Thread.ofPlatform().name("api-call-" + API_THREAD_COUNTER.getAndIncrement()).daemon(true).unstarted(r) }

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
        if (existingJob != null && existingJob.status != CalculationJobStatus.OCID_RESOLVING && existingJob.status != CalculationJobStatus.REQUESTED) {
            log.debug("[jobId={}] Skipping — already in state {}", jobId, existingJob.status)
            return
        }

        // Step 1+2: Resolve OCID → Fetch equipment (thenCompose chain, single block point on cache miss)
        val (ocid, equipmentResponse) = resolveOcidAndFetchEquipment(jobId, payload.userIgn)

        // Step 3: Submit snapshot write to overlap with calculation
        val snapshotData = objectMapper.writeValueAsBytes(equipmentResponse)
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
        val snapshotFuture = CompletableFuture.supplyAsync({ snapshotStore.put(snapshot, snapshotData) }, snapshotWriter)

        // Step 4: Build input items + calculate (overlaps with snapshot file I/O)
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
        calculationInputPort.saveIfAbsent(calcInput)

        // Step 5: Calculate (pure CPU) + pre-compute gzip/hash outside transaction
        val calcResult = pureCalculationPort.calculate(calcInput)
        val resultJson = objectMapper.writeValueAsString(calcResult)
        val resultBytes = resultJson.toByteArray()
        val gzipData = gzipCompress(resultBytes)
        val hash = sha256Hex(resultBytes)

        // Wait for snapshot write completion before transactional save
        val putResult = snapshotFuture.join()

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
        jobService.saveInputSnapshotAndMarkReady(snapshotEntity, jobId, snapshotId)

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

        log.info("[jobId={}] Pipeline completed", jobId)
    }

    /**
     * Resolve OCID and fetch equipment data.
     *
     * OCID cache hit: synchronous fast path (no API call).
     * OCID cache miss: chains OCID API → equipment API via thenCompose,
     * blocking once at .join() instead of twice.
     */
    private fun resolveOcidAndFetchEquipment(jobId: UUID, userIgn: String): Pair<String, EquipmentResponse> {
        val cached = ocidPort.resolveOcid(userIgn)
        if (cached != null) {
            jobService.resolveOcidInPlace(jobId, cached)
            return Pair(cached, equipmentFetchProvider.fetchWithCache(cached))
        }

        return nexonApiClient.getOcidByCharacterName(userIgn)
            .handle { result, ex ->
                if (ex != null) {
                    log.warn("[jobId={}] OCID resolve failed: {}", jobId, ex.message)
                    null
                } else {
                    result
                }
            }
            .thenApply { response ->
                if (response == null || response.ocid.isBlank()) {
                    throw IllegalStateException("OCID resolve returned empty for $userIgn")
                }
                response.ocid
            }
            .thenApply { ocid ->
                jobService.resolveOcidInPlace(jobId, ocid)
                ocid
            }
            .thenCompose { ocid ->
                CompletableFuture.supplyAsync({ Pair(ocid, equipmentFetchProvider.fetchWithCache(ocid)) }, apiCallPool)
            }
            .orTimeout(15, TimeUnit.SECONDS)
            .join()
    }

    private fun handleFailure(jobId: UUID, e: Throwable) {
        val job = jobPort.findJobById(jobId) ?: return
        val errorMsg = (e.message ?: "Unknown error").take(200)

        if (job.retryCount >= job.maxRetries) {
            jobPort.markFailed(jobId, "EXTERNAL_API_ERROR", errorMsg)
        } else {
            jobService.retryExternalApiJob(jobId)
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
        private const val SNAPSHOT_WRITER_POOL_SIZE = 4
        private const val API_CALL_POOL_SIZE = 4
        private val THREAD_COUNTER = java.util.concurrent.atomic.AtomicInteger(0)
        private val API_THREAD_COUNTER = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
