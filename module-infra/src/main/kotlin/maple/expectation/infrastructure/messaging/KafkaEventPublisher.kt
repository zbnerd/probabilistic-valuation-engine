package maple.expectation.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import java.util.concurrent.CompletableFuture
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.port.out.EventPublisher
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.Executor

@Component
@ConditionalOnProperty(
    prefix = "app.event-publisher",
    name = ["type"],
    havingValue = "kafka",
    matchIfMissing = false,
)
class KafkaEventPublisher(
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    @Qualifier("taskExecutor") private val taskExecutor: Executor,
) : EventPublisher {

    private val logger = LoggerFactory.getLogger(KafkaEventPublisher::class.java)

    @PostConstruct
    fun warnStubMode() {
        logger.error(
            "[KafkaEventPublisher] STUB MODE ACTIVE — events will NOT be published to Kafka. " +
                "Set app.event-publisher.type=pgmq (or implement KafkaTemplate here). " +
                "Detected at startup with app.event-publisher.type=kafka.",
        )
    }

    override fun publish(topic: String, event: IntegrationEvent<*>) {
        executor.executeOrCatch(
            {
                publishInternal(topic, event)
                null
            },
            { e ->
                logger.error("[KafkaEventPublisher] Publish failed for topic: {}", topic, e)
                null
            },
            TaskContext.of("KafkaEventPublisher", "Publish", topic),
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
            jsonPayload,
        )
    }

    override fun publishAsync(topic: String, event: IntegrationEvent<*>): CompletableFuture<Void> {
        logger.warn(
            "[KafkaEventPublisher] STUB MODE - Async publish not implemented. topic={}, eventId={}",
            topic,
            event.eventId,
        )

        return CompletableFuture.runAsync({ publish(topic, event) }, taskExecutor)
    }
}
