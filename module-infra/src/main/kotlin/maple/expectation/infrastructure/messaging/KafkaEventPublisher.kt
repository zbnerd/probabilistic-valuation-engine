package maple.expectation.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.port.out.EventPublisher
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "app.event-publisher",
    name = ["type"],
    havingValue = "kafka",
    matchIfMissing = false,
)
class KafkaEventPublisher(
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val executor: LogicExecutor,
) : EventPublisher {

    private val logger = LoggerFactory.getLogger(KafkaEventPublisher::class.java)

    override fun publish(topic: String, event: IntegrationEvent<*>) {
        executor.executeVoid(
            { publishInternal(topic, event) },
            TaskContext.of("KafkaEventPublisher", "Publish", topic),
        )
    }

    private fun publishInternal(topic: String, event: IntegrationEvent<*>) {
        val jsonPayload = objectMapper.writeValueAsString(event)
        val key = event.jobId ?: event.eventId

        kafkaTemplate.send(topic, key, jsonPayload)
            .whenComplete { _, ex ->
                if (ex != null) {
                    logger.error(
                        "[KafkaEventPublisher] Publish failed for topic={}, eventId={}: {}",
                        topic,
                        event.eventId,
                        ex.message,
                    )
                } else {
                    logger.debug("[KafkaEventPublisher] Published eventId={} to topic={}", event.eventId, topic)
                }
            }
    }

    override fun publishAsync(topic: String, event: IntegrationEvent<*>): CompletableFuture<Void> {
        val jsonPayload = executor.executeOrDefault(
            { objectMapper.writeValueAsString(event) },
            "",
            TaskContext.of("KafkaEventPublisher", "Serialize", topic),
        )

        val key = event.jobId ?: event.eventId
        return kafkaTemplate.send(topic, key, jsonPayload)
            .thenAccept {}
            .exceptionally { ex ->
                logger.error(
                    "[KafkaEventPublisher] Async publish failed for topic={}, eventId={}: {}",
                    topic,
                    event.eventId,
                    ex.message,
                )
                null
            }
    }
}
