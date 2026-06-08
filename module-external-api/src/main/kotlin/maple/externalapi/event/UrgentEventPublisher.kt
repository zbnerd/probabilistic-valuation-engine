package maple.externalapi.event

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.util.StringMaskingUtils.maskIgn
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * Serializes urgent domain events to JSON and publishes them to Kafka. The
 * urgent consumer never calls `kafkaTemplate.send` or
 * `objectMapper.writeValueAsString` directly.
 */
@Component
class UrgentEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.urgent.not-found-topic}")
    private val notFoundTopic: String,
    @Value("\${external-api.urgent.chunk-ready-topic}")
    private val urgentChunkReadyTopic: String,
) {
    private val log = LoggerFactory.getLogger(UrgentEventPublisher::class.java)

    fun publishChunkReady(event: SnapshotChunkReadyEvent): CompletableFuture<Void> {
        val payload = objectMapper.writeValueAsString(event)
        return kafkaTemplate.send(urgentChunkReadyTopic, event.kafkaKey(), payload)
            .thenAccept {
                log.info(
                    "[Urgent] published chunk: endpoint={}, objectKey={}",
                    event.endpoint,
                    event.objectKey,
                )
            }
    }

    fun publishNotFound(userIgn: String, reason: String, occurredAt: Instant): CompletableFuture<Void> {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "userIgn" to userIgn,
                "reason" to reason,
                "occurredAt" to occurredAt.toString(),
            ),
        )
        return kafkaTemplate.send(notFoundTopic, userIgn, payload)
            .thenAccept {
                log.info("[Urgent] published not-found: userIgn={}", maskIgn(userIgn))
            }
    }
}
