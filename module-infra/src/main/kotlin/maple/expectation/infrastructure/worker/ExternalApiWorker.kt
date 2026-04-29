package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.PureCalculationPort
import maple.expectation.core.port.out.QueueNames
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.infrastructure.converter.EquipmentResponseToCalculationInputConverter
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
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
    private val executionService: CalculationExecutionService,
) : PgmqWorker<ExternalApiJobPayload>(pgmqClient, executor, workerConfig, meterRegistry, queueMetrics, lifecycleWrapper) {

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
        jobService.saveSnapshotInPlace(snapshotEntity)
        jobService.markSnapshotReadyInPlace(jobId, snapshotId)

        // Step 4: Calculate (pure CPU, ~ms)
        val started = executionService.startCalculation(jobId, "ExternalApiWorker")
        if (!started) {
            log.warn("[jobId={}] Could not start calculation", jobId)
            return
        }

        val input = calculationInputPort.findByJobId(jobId)
        if (input == null) {
            log.error("[jobId={}] CalculationInput not found after save", jobId)
            jobPort.markFailed(jobId, "INPUT_NOT_FOUND", "CalculationInput not found for job")
            return
        }

        val calcResult = pureCalculationPort.calculate(input)
        val resultJson = objectMapper.writeValueAsString(calcResult)

        executionService.completeCalculationWithResult(
            jobId = jobId,
            resultJson = resultJson,
            characterClass = input.characterClass,
            presetNo = payload.presetNo,
            characterId = ocid,
        )

        log.info("[jobId={}] Pipeline completed", jobId)
    }

    private fun resolveOcid(jobId: UUID, userIgn: String): String {
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

    companion object {
        private val log = LoggerFactory.getLogger(ExternalApiWorker::class.java)
    }
}
