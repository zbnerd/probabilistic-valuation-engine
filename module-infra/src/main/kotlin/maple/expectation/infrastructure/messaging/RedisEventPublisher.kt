package maple.expectation.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import maple.expectation.core.port.out.EventPublisher
import maple.expectation.core.port.out.MessageQueue
import maple.expectation.domain.event.IntegrationEvent
import maple.expectation.error.exception.QueuePublishException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Redis-based event publisher implementation.
 *
 * <p><strong>Concrete Strategy A:</strong> Uses {@link MessageQueue} for queue-based messaging.
 * This is the default implementation for Phase 1.
 *
 * <p><strong>Configuration:</strong> Activated when {@code app.event-publisher.type=redis}
 * (default: true). To switch to Kafka in Phase 8, change to {@code type=kafka}.
 *
 * <p><strong>SOLID Compliance:</strong>
 *
 * <ul>
 *   <li><b>DIP:</b> Implements {@link EventPublisher} interface
 *   <li><b>SRP:</b> Single responsibility - Redis publishing logic
 *   <li><b>OCP:</b> Can be replaced by KafkaEventPublisher without changing business logic
 * </ul>
 *
 * <h3>CLAUDE.md Section 12 Compliance</h3>
 *
 * <ul>
 *   <li>Uses LogicExecutor.executeWithTranslation() for exception handling
 *   <li>No raw try-catch blocks in business logic
 * </ul>
 *
 * <h3>Redis vs Kafka Trade-off:</h3>
 *
 * <table border="1">
 *   <tr><th>Aspect</th><th>Redis (Current)</th><th>Kafka (Phase 8)</th></tr>
 *   <tr><td>Throughput</td><td>~10K msg/s</td><td>~100K+ msg/s</td></tr>
 *   <tr><td>Persistence</td><td>AOF (configurable)</td><td>Log-based (durable)</td></tr>
 *   <tr><td>Replay</td><td>Limited</td><td>Offset-based replay</td></tr>
 *   <tr><td>Operations</td><td>Already using Redis</td><td>New infrastructure</td></tr>
 *   <tr><td>Cost</td><td>Free (using existing)</td><td>$$ (new cluster)</td></tr>
 * </table>
 *
 * <h3>Migration to Kafka (Phase 8):</h3>
 *
 * <ol>
 *   <li>Add {@code spring-kafka} dependency
 *   <li>Implement {@code KafkaEventPublisher}
 *   <li>Change configuration: {@code app.event-publisher.type=kafka}
 *   <li>Zero code changes in business logic (DIP works!)
 * </ol>
 *
 * @see EventPublisher
 * @see MessageQueue
 * @see maple.expectation.infrastructure.messaging.RedisMessageQueue
 * @see ADR-018 Strategy Pattern for ACL
 */
@Component
@ConditionalOnProperty(
    prefix = "app.event-publisher",
    name = ["type"],
    havingValue = "redis",
    matchIfMissing = true
)
class RedisEventPublisher(
    @Qualifier("integrationEventQueue") private val messageQueue: MessageQueue<String>,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor
) : EventPublisher {

    companion object {
        private val log = LoggerFactory.getLogger(RedisEventPublisher::class.java)
    }

    override fun publish(topic: String, event: IntegrationEvent<*>) {
        executor.executeVoid(
            { publishInternal(topic, event) },
            TaskContext.of("RedisEventPublisher", "Publish", topic)
        )
    }

    override fun publishAsync(topic: String, event: IntegrationEvent<*>): CompletableFuture<Void> {
        // Redis publish is already fast (in-memory), so we use the default async wrapper
        // For Kafka implementation, this would use KafkaTemplate.send() which returns CompletableFuture
        return CompletableFuture.runAsync { publish(topic, event) }
    }

    /**
     * Internal publish implementation.
     *
     * <p>Handles serialization and queue operations, converting checked exceptions to
     * QueuePublishException as per CLAUDE.md Section 11.
     */
    private fun publishInternal(topic: String, event: IntegrationEvent<*>) {
        try {
            // Serialize IntegrationEvent to JSON
            val jsonPayload: String = objectMapper.writeValueAsString(event)

            // Offer to Redis queue
            val offered = messageQueue.offer(jsonPayload)

            if (!offered) {
                log.warn(
                    "[RedisEventPublisher] Queue full, could not publish to topic {}: eventId={}, eventType={}",
                    topic,
                    event.eventId,
                    event.eventType
                )
                throw QueuePublishException(
                    String.format("Redis queue full: topic=%s, eventType=%s", topic, event.eventType)
                )
            }

            log.debug(
                "[RedisEventPublisher] Published to queue {}: eventId={}, eventType={}",
                topic,
                event.eventId,
                event.eventType
            )
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            // Section 11: Convert checked exception to domain exception
            throw QueuePublishException("Failed to serialize event for topic: $topic", e)
        }
    }
}
