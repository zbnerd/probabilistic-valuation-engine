package maple.expectation.core.port.out

import java.util.concurrent.CompletableFuture
import maple.expectation.core.domain.event.IntegrationEvent

/**
 * Strategy interface for event publishing.
 *
 * <p><strong>Strategy Pattern:</strong> Concrete implementations (PGMQ, Kafka) are interchangeable
 * via configuration. This enables OCP compliance - open for extension (new publishers), closed for
 * modification (existing code unchanged).
 *
 * <p><strong>DIP Compliance:</strong> Business logic depends on this abstraction, not concrete
 * PGMQ/Kafka implementations.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Business code (depends on abstraction)
 * @Service
 * class NexonDataCollector {
 *     private val eventPublisher: EventPublisher  // Interface, not concrete class
 *
 *     fun collect(ocid: String) {
 *         val event: IntegrationEvent<CharacterData> = ...
 *         eventPublisher.publish("character-data", event)  // Polymorphic call
 *     }
 * }
 *
 * // Configuration (selects implementation)
 * @Configuration
 * class MessagingConfig {
 *     @Bean
 *     @ConditionalOnProperty(name = ["app.event-publisher.type"], havingValue = "pgmq")
 *     fun pgmqEventPublisher(): EventPublisher {
 *         return PgmqEventPublisher(...)
 *     }
 *
 *     @Bean
 *     @ConditionalOnProperty(name = ["app.event-publisher.type"], havingValue = "kafka")
 *     fun kafkaEventPublisher(): EventPublisher {
 *         return KafkaEventPublisher(...)
 *     }
 * }
 * }</pre>
 *
 * @see maple.expectation.infrastructure.messaging.PgmqStreamPublisher
 * @see ADR-018 Strategy Pattern for ACL
 */
interface EventPublisher {

    /**
     * Publish an event to the message broker.
     *
     * <p><strong>Synchronous blocking call:</strong> Waits for publish confirmation before returning.
     * Use {@link #publishAsync(String, IntegrationEvent)} for non-blocking behavior.
     *
     * @param topic Topic name (e.g., "character-data", "nexon-api-events")
     * @param event Event to publish (wrapped in IntegrationEvent envelope)
     * @throws maple.expectation.global.error.exception.QueuePublishException if publish fails
     */
    fun publish(topic: String, event: IntegrationEvent<*>)

    /**
     * Publish an event asynchronously (non-blocking).
     *
     * <p><strong>Fire-and-forget semantics:</strong> Returns immediately without waiting for publish
     * confirmation. The CompletableFuture completes when the publish operation succeeds or fails.
     *
     * <p><strong>Use case:</strong> High-throughput scenarios where blocking on publish would cause
     * performance degradation (e.g., REST ingestion layer).
     *
     * <p><strong>Error handling:</strong> Exceptions are delivered via CompletableFuture. Callers
     * should handle exceptionally() if needed:
     *
     * <pre>{@code
     * eventPublisher.publishAsync(topic, event)
     *     .exceptionally { ex ->
     *         log.error("Publish failed", ex)
     *         null  // or fallback logic
     *     }
     * }</pre>
     *
     * @param topic Topic name
     * @param event Event to publish
     * @return CompletableFuture that completes when published
     */
    fun publishAsync(topic: String, event: IntegrationEvent<*>): CompletableFuture<Void> = CompletableFuture.runAsync { publish(topic, event) }
}
