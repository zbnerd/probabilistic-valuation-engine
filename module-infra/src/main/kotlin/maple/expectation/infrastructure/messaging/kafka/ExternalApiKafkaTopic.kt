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
class ExternalApiKafkaTopic(
    outboxRepository: KafkaOutboxEventRepository,
    objectMapper: ObjectMapper,
    executor: LogicExecutor,
    kafkaTemplate: KafkaTemplate<String, String>,
    properties: KafkaPipelineProperties,
) : KafkaTopicGroup(outboxRepository, objectMapper, executor, kafkaTemplate, properties),
    PipelineTopic {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = KafkaTopicNames.EXTERNAL_API_REQUESTED
    override val dltTopicName: String = KafkaTopicNames.EXTERNAL_API_REQUESTED_DLT
    override val consumerGroup: String = "maple-external-api"

    override val requiredFields: List<String> = listOf("schemaVersion", "jobId", "requestKey", "userIgn", "presetNo")
    override val schemaVersion: Int = 1
    override val leaseDurationSeconds: Long = properties.consumer.externalApi.leaseDurationSeconds

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

        val jobId = parsed.path("jobId").asText()
        log.info("[ExternalApiKafkaTopic] Processing jobId={} from {}[{}]", jobId, topic, partition)

        // PR-2: DB CAS claim + Nexon API call + snapshot staging
        log.info("[ExternalApiKafkaTopic] SKIPPED (PR-1 skeleton) jobId={}", jobId)

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
        // PR-2: DB CAS claim API_REQUESTED → API_IN_PROGRESS + locked_until
        log.debug("[ExternalApiKafkaTopic] claimJob stub for jobId={}", jobId)
        return true
    }
}
