package maple.expectation.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.port.out.EventPublisher
import maple.expectation.domain.event.IntegrationEvent
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

@Component
@ConditionalOnProperty(
    prefix = "app.event-publisher",
    name = ["type"],
    havingValue = "kafka",
    matchIfMissing = false
)
class KafkaEventPublisher(
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor
) : EventPublisher {

    private val logger = LoggerFactory.getLogger(KafkaEventPublisher::class.java)

    override fun publish(topic: String, event: IntegrationEvent<*>) {
        executor.executeVoidJava(
            {
                try {
                    publishInternal(topic, event)
                } catch (e: Exception) {
                    logger.error("[KafkaEventPublisher] Publish failed for topic: {}", topic, e)
                }
            },
            TaskContext.of("KafkaEventPublisher", "Publish", topic)
        )
    }

    private fun publishInternal(topic: String, event: IntegrationEvent<*>) {
        val jsonPayload = objectMapper.writeValueAsString(event)

        logger.warn(
            "[KafkaEventPublisher] STUB MODE - Event not published to Kafka. " +
                "topic={}, eventId={}, eventType={}, payload={}",
            topic,
            event.eventId,
            event.eventType,
            jsonPayload
        )
    }

    override fun publishAsync(topic: String, event: IntegrationEvent<*>): CompletableFuture<Void> {
        logger.warn(
            "[KafkaEventPublisher] STUB MODE - Async publish not implemented. topic={}, eventId={}",
            topic,
            event.eventId
        )

        return CompletableFuture.runAsync { publish(topic, event) }
    }
}
