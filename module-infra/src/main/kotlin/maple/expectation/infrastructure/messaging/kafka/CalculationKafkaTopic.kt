package maple.expectation.infrastructure.messaging.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.KafkaTopicNames
import maple.expectation.core.port.out.PureCalculationPort
import maple.expectation.infrastructure.config.KafkaPipelineProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.job.CalculationExecutionService
import maple.expectation.infrastructure.persistence.repository.KafkaOutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "app.kafka.pipeline", name = ["enabled"], havingValue = "true")
class CalculationKafkaTopic(
    outboxRepository: KafkaOutboxEventRepository,
    objectMapper: ObjectMapper,
    executor: LogicExecutor,
    kafkaTemplate: KafkaTemplate<String, String>,
    properties: KafkaPipelineProperties,
    private val jobPort: CalculationJobPort,
    private val calculationInputPort: CalculationInputPort,
    private val pureCalculationPort: PureCalculationPort,
    private val executionService: CalculationExecutionService,
) : KafkaTopicGroup(outboxRepository, objectMapper, executor, kafkaTemplate, properties),
    PipelineTopic {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = KafkaTopicNames.CALCULATION_REQUESTED
    override val dltTopicName: String = KafkaTopicNames.CALCULATION_REQUESTED_DLT
    override val consumerGroup: String = "maple-calculation"

    override val requiredFields: List<String> = listOf("schemaVersion", "jobId", "requestKey", "userIgn", "presetNo", "characterId", "characterClass")
    override val schemaVersion: Int = 1
    override val leaseDurationSeconds: Long
        get() = properties.consumer.calculation.leaseDurationSeconds

    @KafkaListener(
        topics = [KafkaTopicNames.CALCULATION_REQUESTED],
        groupId = "maple-calculation",
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
            TaskContext.of("CalculationKafkaTopic", "Consume"),
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
        val characterId = parsed.path("characterId").asText()
        val characterClass = parsed.path("characterClass").asText()

        executor.executeOrCatch(
            { processJob(jobId, userIgn, presetNo, characterId, characterClass) },
            { e -> handleJobError(jobId, e) },
            TaskContext.of("CalculationKafkaTopic", "Process", jobId.toString()),
        )

        ack.acknowledge()
    }

    private fun processJob(jobId: UUID, userIgn: String, presetNo: Int, characterId: String, characterClass: String) {
        val existingJob = jobPort.findJobById(jobId)

        // Terminal states: skip entirely
        if (existingJob != null && (existingJob.status == CalculationJobStatus.COMPLETED || existingJob.status == CalculationJobStatus.FAILED)) {
            log.debug("[jobId={}] Skipping — terminal state {}", jobId, existingJob.status)
            return
        }

        // Only process SNAPSHOT_READY jobs
        if (existingJob != null && existingJob.status != CalculationJobStatus.SNAPSHOT_READY) {
            log.debug("[jobId={}] Skipping — state {} (expected SNAPSHOT_READY)", jobId, existingJob.status)
            return
        }

        // CAS claim
        val claimed = jobPort.lockForProcessing(jobId, "kafka-calc", CalculationJobStatus.SNAPSHOT_READY)
        if (!claimed) {
            log.info("[jobId={}] Already claimed by another consumer", jobId)
            return
        }

        // Pipeline with guaranteed unlock
        executor.executeWithFinally(
            { processCalculation(jobId, userIgn, presetNo, characterId, characterClass) },
            {
                executor.executeVoid(
                    { jobPort.unlock(jobId) },
                    TaskContext.of("CalculationKafkaTopic", "Unlock", jobId.toString()),
                )
            },
            TaskContext.of("CalculationKafkaTopic", "Pipeline", jobId.toString()),
        )
    }

    private fun processCalculation(jobId: UUID, userIgn: String, presetNo: Int, characterId: String, characterClass: String) {
        // Load input [DB read, no TX]
        val input = stage("LoadInput", jobId.toString()) {
            calculationInputPort.findByJobId(jobId)
                ?: error("Calculation input missing for job: $jobId")
        }

        // CPU-bound calculation [no TX]
        val calcResult = stage("PureCalculate", userIgn) {
            pureCalculationPort.calculate(input)
        }

        // Serialize + gzip + hash [CPU, no TX]
        val resultBytes = stage("SerializeResult", userIgn) {
            objectMapper.writeValueAsString(calcResult).toByteArray()
        }
        val gzipData = stage("GzipResult", userIgn) {
            gzipCompress(resultBytes)
        }
        val hash = stage("HashResult", userIgn) {
            sha256Hex(resultBytes)
        }

        // Complete: SNAPSHOT_READY → COMPLETED + result save + outbox insert [TX]
        stage("CompleteCalculation", jobId.toString()) {
            executionService.completeCalculation(
                jobId = jobId,
                gzipData = gzipData,
                hash = hash,
                originalSize = resultBytes.size,
                compressedSize = gzipData.size,
                characterClass = characterClass,
                presetNo = presetNo,
                characterId = characterId,
            )
        }

        log.info("[jobId={}] Calculation completed", jobId)
    }

    private fun handleJobError(jobId: UUID, e: Throwable) {
        log.error("[jobId={}] Calculation pipeline error: {}", jobId, e.message)
        val job = jobPort.findJobById(jobId) ?: return
        val errorCode = "CALCULATION_ERROR"
        val errorMsg = (e.message ?: "Unknown error").take(200)

        if (job.retryCount >= job.maxRetries) {
            executor.executeVoid(
                { jobPort.markFailed(jobId, errorCode, errorMsg) },
                TaskContext.of("CalculationKafkaTopic", "MarkFailed", jobId.toString()),
            )
        } else {
            executor.executeVoid(
                { executionService.handleCalculationFailure(jobId, errorCode, errorMsg) },
                TaskContext.of("CalculationKafkaTopic", "HandleFailure", jobId.toString()),
            )
        }
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

    override fun claimJob(jobId: String): Boolean = jobPort.lockForProcessing(UUID.fromString(jobId), "kafka-calc", CalculationJobStatus.SNAPSHOT_READY)

    private fun <T> stage(name: String, key: String, block: () -> T): T = executor.execute(
        { block() },
        TaskContext.of("CalculationKafkaTopic", name, key),
    )

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
