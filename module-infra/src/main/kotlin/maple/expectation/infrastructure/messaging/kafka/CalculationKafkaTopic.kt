package maple.expectation.infrastructure.messaging.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.port.out.KafkaTopicNames
import maple.expectation.infrastructure.config.KafkaPipelineProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
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
) : KafkaTopicGroup(outboxRepository, objectMapper, executor, kafkaTemplate, properties),
    PipelineTopic {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = KafkaTopicNames.CALCULATION_REQUESTED
    override val dltTopicName: String = KafkaTopicNames.CALCULATION_REQUESTED_DLT
    override val consumerGroup: String = "maple-calculation"

    override val requiredFields: List<String> = listOf("schemaVersion", "jobId", "requestKey", "userIgn", "presetNo", "characterId", "characterClass", "snapshotId")
    override val schemaVersion: Int = 1
    override val leaseDurationSeconds: Long = 0L // CPU-bound, no lease needed

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

        val jobId = parsed.path("jobId").asText()
        log.info("[CalculationKafkaTopic] Processing jobId={} from {}[{}]", jobId, topic, partition)

        // PR-3: DB CAS claim SNAPSHOT_READY → CALCULATING + calculation + result persist
        log.info("[CalculationKafkaTopic] SKIPPED (PR-1 skeleton) jobId={}", jobId)

        ack.acknowledge()
    }

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
        // PR-3: DB CAS claim SNAPSHOT_READY → CALCULATING
        log.debug("[CalculationKafkaTopic] claimJob stub for jobId={}", jobId)
        return true
    }
}
