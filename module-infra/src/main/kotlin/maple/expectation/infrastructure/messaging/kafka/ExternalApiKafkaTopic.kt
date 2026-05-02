package maple.expectation.infrastructure.messaging.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.dto.v4.EquipmentItem
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.core.port.out.KafkaTopicNames
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.error.exception.CharacterNotFoundException
import maple.expectation.infrastructure.config.KafkaPipelineProperties
import maple.expectation.infrastructure.converter.EquipmentResponseToCalculationInputConverter
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.KafkaOutboxEventRepository
import maple.expectation.infrastructure.provider.EquipmentFetchProvider
import maple.expectation.util.ExceptionUtils
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException

@Component
@ConditionalOnProperty(prefix = "app.kafka.pipeline", name = ["enabled"], havingValue = "true")
class ExternalApiKafkaTopic(
    outboxRepository: KafkaOutboxEventRepository,
    objectMapper: ObjectMapper,
    executor: LogicExecutor,
    kafkaTemplate: KafkaTemplate<String, String>,
    properties: KafkaPipelineProperties,
    private val jobPort: CalculationJobPort,
    private val ocidPort: CharacterOcidPort,
    private val nexonApiClient: NexonApiClient,
    private val equipmentFetchProvider: EquipmentFetchProvider,
    private val snapshotStore: SnapshotObjectStore,
    private val jobService: CalculationJobService,
    private val converter: EquipmentResponseToCalculationInputConverter,
    private val calculationInputPort: CalculationInputPort,
) : KafkaTopicGroup(outboxRepository, objectMapper, executor, kafkaTemplate, properties),
    PipelineTopic {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = KafkaTopicNames.EXTERNAL_API_REQUESTED
    override val dltTopicName: String = KafkaTopicNames.EXTERNAL_API_REQUESTED_DLT
    override val consumerGroup: String = "maple-external-api"

    override val requiredFields: List<String> = listOf("schemaVersion", "jobId", "requestKey", "userIgn", "presetNo")
    override val schemaVersion: Int = 1
    override val leaseDurationSeconds: Long
        get() = properties.consumer.externalApi.leaseDurationSeconds

    @KafkaListener(
        topics = [KafkaTopicNames.EXTERNAL_API_REQUESTED],
        groupId = "maple-external-api",
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun consume(
        @Payload payload: String,
        @Header("kafka_receivedTopic") topic: String,
        @Header("kafka_receivedPartitionId") partition: Int,
        @Header("kafka_offset") offset: Long,
        ack: Acknowledgment,
    ) {
        executor.executeVoid(
            { processMessage(payload, topic, partition, offset, ack) },
            TaskContext.of("ExternalApiKafkaTopic", "Consume"),
        )
    }

    private fun processMessage(payload: String, topic: String, partition: Int, offset: Long, ack: Acknowledgment) {
        val parsed = parseAndValidate(payload)
        if (parsed == null) {
            sendToDlt(payload, topic, partition, offset, consumerGroup, "VALIDATION_FAILED", "Parse/validation error")
            ack.acknowledge()
            return
        }

        val jobId = UUID.fromString(parsed.path("jobId").asText())
        val userIgn = parsed.path("userIgn").asText()
        val presetNo = parsed.path("presetNo").asInt()

        executor.executeOrCatch(
            { processJob(jobId, userIgn, presetNo) },
            { e -> handleJobError(jobId, userIgn, presetNo, e) },
            TaskContext.of("ExternalApiKafkaTopic", "Process", jobId.toString()),
        )

        ack.acknowledge()
    }

    private fun processJob(jobId: UUID, userIgn: String, presetNo: Int) {
        val existingJob = jobPort.findJobById(jobId)

        // Terminal states: skip entirely
        if (existingJob != null && (existingJob.status == CalculationJobStatus.COMPLETED || existingJob.status == CalculationJobStatus.FAILED)) {
            log.debug("[jobId={}] Skipping — terminal state {}", jobId, existingJob.status)
            return
        }

        // SNAPSHOT_READY: API+snapshot already done, dispatch to calculation
        if (existingJob != null && existingJob.status == CalculationJobStatus.SNAPSHOT_READY) {
            val characterId = existingJob.ocid
            if (characterId == null) {
                log.warn("[jobId={}] No OCID in SNAPSHOT_READY, cannot dispatch calculation", jobId)
                return
            }
            val characterClass = stage("LoadCharacterClass", jobId.toString()) {
                calculationInputPort.findByJobId(jobId)?.characterClass ?: ""
            }
            dispatchCalculationRequested(jobId, userIgn, presetNo, characterId, characterClass)
            return
        }

        // Not processable
        if (existingJob != null && !isExternalApiProcessable(existingJob.status)) {
            log.debug("[jobId={}] Skipping — state {}", jobId, existingJob.status)
            return
        }

        // CAS claim
        val fromStatus = existingJob?.status ?: CalculationJobStatus.OCID_RESOLVING
        val workerId = "kafka-ext-api"
        val claimed = jobPort.lockForProcessing(jobId, workerId, fromStatus)
        if (!claimed) {
            log.info("[jobId={}] Already claimed by another consumer", jobId)
            return
        }

        // Pipeline with guaranteed unlock
        executor.executeWithFinally(
            { processExternalApiPipeline(jobId, userIgn, presetNo, existingJob?.ocid) },
            {
                executor.executeVoid(
                    { jobPort.unlock(jobId) },
                    TaskContext.of("ExternalApiKafkaTopic", "Unlock", jobId.toString()),
                )
            },
            TaskContext.of("ExternalApiKafkaTopic", "Pipeline", jobId.toString()),
        )
    }

    private fun processExternalApiPipeline(jobId: UUID, userIgn: String, presetNo: Int, cachedOcid: String?) {
        // Step 1+2: Resolve OCID → Fetch equipment
        val (ocid, equipmentResponse) = stage("ResolveAndFetch", userIgn) {
            resolveOcidAndFetchEquipment(jobId, userIgn, cachedOcid)
        }

        // Step 3: Serialize snapshot + write to object store
        val snapshotData = stage("SerializeSnapshot", userIgn) {
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
            presetNo = presetNo,
            expiresAt = Instant.now().plusSeconds(86400),
        )
        val putResult = stage("SnapshotPut", jobId.toString()) {
            snapshotStore.put(snapshot, snapshotData)
        }

        // Step 4: Build and persist calculation input
        val inputItems = stage("BuildCalculationInput", userIgn) {
            convertItems(equipmentResponse)
        }
        val characterClass = equipmentResponse.characterClass ?: ""
        val calcInput = CalculationInput(
            jobId = jobId.toString(),
            userIgn = userIgn,
            characterClass = characterClass,
            presetNo = presetNo,
            items = inputItems,
        )
        stage("SaveCalculationInput", jobId.toString()) {
            calculationInputPort.saveIfAbsent(calcInput)
        }

        // Step 5+6: Save snapshot metadata + transition to SNAPSHOT_READY [TX]
        val snapshotEntity = CalculationSnapshotEntity(
            snapshotId = snapshotId,
            jobId = jobId,
            objectKey = objectKey,
            storageType = "LOCAL",
            characterId = ocid,
            presetNo = presetNo,
            compressedSize = putResult.compressedSize,
            originalSize = snapshotData.size.toLong(),
            hash = putResult.hash,
            expiresAt = snapshot.expiresAt,
        )
        stage("SaveSnapshotAndMarkReady", jobId.toString()) {
            jobService.saveInputSnapshotAndMarkReady(snapshotEntity, jobId, snapshotId)
        }

        // Step 7: Dispatch calculation.requested via outbox
        dispatchCalculationRequested(jobId, userIgn, presetNo, ocid, characterClass)

        log.info("[jobId={}] External API pipeline completed, calculation dispatched", jobId)
    }

    private fun handleJobError(jobId: UUID, userIgn: String, presetNo: Int, e: Throwable) {
        if (isCharacterNotFound(e)) {
            val msg = (ExceptionUtils.unwrapAsyncException(e)?.message ?: "Character not found").take(200)
            executor.executeVoid(
                { jobPort.markFailed(jobId, "CHARACTER_NOT_FOUND", msg) },
                TaskContext.of("ExternalApiKafkaTopic", "MarkFailed", jobId.toString()),
            )
            log.warn("[jobId={}] Character not found, marked FAILED: {}", jobId, msg)
        } else {
            log.error("[jobId={}] External API pipeline error: {}", jobId, e.message)
            handlePipelineFailure(jobId, e)
        }
    }

    private fun handlePipelineFailure(jobId: UUID, e: Throwable) {
        val job = jobPort.findJobById(jobId) ?: return
        val errorCode = classifyExternalApiError(e)
        val errorMsg = (e.message ?: "Unknown error").take(200)

        if (job.retryCount >= job.maxRetries) {
            executor.executeVoid(
                { jobPort.markFailed(jobId, errorCode, errorMsg) },
                TaskContext.of("ExternalApiKafkaTopic", "MarkFailed", jobId.toString()),
            )
        } else {
            executor.executeVoid(
                { jobPort.incrementRetry(jobId, errorCode) },
                TaskContext.of("ExternalApiKafkaTopic", "IncrementRetry", jobId.toString()),
            )
            log.info("[jobId={}] Pipeline error recorded, retry={}/{}, code={}", jobId, job.retryCount + 1, job.maxRetries, errorCode)
        }
    }

    // ===== OCID Resolve + Equipment Fetch (from ExternalApiWorker) =====

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
                    Pair(ocid, equipmentFetchProvider.fetchWithCache(ocid))
                })
            }
            .orTimeout(15, TimeUnit.SECONDS)
            .join()
    }

    // ===== Outbox Dispatch =====

    private fun dispatchCalculationRequested(
        jobId: UUID,
        userIgn: String,
        presetNo: Int,
        characterId: String,
        characterClass: String,
    ) {
        val payload = mapOf(
            "schemaVersion" to 1,
            "jobId" to jobId.toString(),
            "requestKey" to buildRequestKey(userIgn, presetNo),
            "userIgn" to userIgn,
            "presetNo" to presetNo,
            "characterId" to characterId,
            "characterClass" to characterClass,
            "createdAt" to Instant.now().toString(),
        )

        outboxRepository.insertIfAbsent(
            id = UUID.randomUUID(),
            eventType = KafkaTopicNames.CALCULATION_REQUESTED,
            aggregateId = jobId,
            aggregateType = "calculation_job",
            topic = KafkaTopicNames.CALCULATION_REQUESTED,
            partitionKey = jobId.toString(),
            payload = objectMapper.writeValueAsString(payload),
        )

        log.debug("[ExternalApiKafkaTopic] Calculation request enqueued: jobId={}", jobId)
    }

    // ===== Helpers =====

    override fun parseAndValidate(payload: String): JsonNode? {
        val node = runCatching { objectMapper.readTree(payload) }
            .getOrElse { return null }

        val version = node.path("schemaVersion").asInt(-1)
        if (version != schemaVersion) return null

        val missing = requiredFields.filter { !node.has(it) || node.path(it).isNull }
        if (missing.isNotEmpty()) return null

        return node
    }

    override fun claimJob(jobId: String): Boolean {
        val fromStatus = jobPort.findJobById(UUID.fromString(jobId))?.status ?: CalculationJobStatus.OCID_RESOLVING
        return jobPort.lockForProcessing(UUID.fromString(jobId), "kafka-ext-api", fromStatus)
    }

    private fun convertItems(equipmentResponse: EquipmentResponse): List<EquipmentItem> {
        val items = equipmentResponse.itemEquipment ?: return emptyList()
        return items.map { convertItem(it) }
    }

    private fun convertItem(item: Any): EquipmentItem {
        val itemMap = objectMapper.convertValue(item, Map::class.java) as Map<*, *>
        return converter.convertItem(itemMap)
    }

    private fun generateObjectKey(jobId: UUID): String {
        val now = Instant.now()
        val zoned = now.atZone(ZoneOffset.UTC)
        val datePath = "%04d/%02d/%02d".format(zoned.year, zoned.monthValue, zoned.dayOfMonth)
        return "snapshots/$datePath/$jobId.gz"
    }

    private fun buildRequestKey(userIgn: String, presetNo: Int): String = "calc:v1:ign:${userIgn.lowercase()}:preset:$presetNo:schema:1"

    private fun <T> stage(name: String, key: String, block: () -> T): T = executor.execute(
        { block() },
        TaskContext.of("ExternalApiKafkaTopic", name, key),
    )

    private fun isCharacterNotFound(e: Throwable): Boolean = ExceptionUtils.containsCause(e, CharacterNotFoundException::class.java)

    private fun isExternalApiProcessable(status: CalculationJobStatus): Boolean = status == CalculationJobStatus.REQUESTED ||
        status == CalculationJobStatus.OCID_RESOLVING ||
        status == CalculationJobStatus.API_REQUESTED ||
        status == CalculationJobStatus.RETRYING

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
}
