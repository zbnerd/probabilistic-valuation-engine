package maple.expectation.core.port.out

import java.util.concurrent.CompletableFuture
import maple.expectation.core.domain.event.IntegrationEvent

/**
 * Strategy interface for event publishing.
 *
 * <p>Concrete adapters (any message broker) are interchangeable via
 * configuration. Business logic depends on this abstraction, not on
 * concrete publisher implementations.
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
    fun publishAsync(topic: String, event: IntegrationEvent<*>): CompletableFuture<Void>
}
