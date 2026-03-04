package maple.expectation.core.domain.event

import java.time.Instant
import java.util.UUID

/**
 * Standardized event envelope for all integration events.
 *
 * <p>Ensures consistent metadata (eventId, eventType, timestamp) across all message types. This is
 * part of the Anti-Corruption Layer (ACL) that isolates external systems from internal processing
 * pipelines.
 *
 * <p><strong>SOLID Compliance:</strong>
 *
 * <ul>
 *   <li><b>SRP:</b> Single responsibility - event metadata container
 *   <li><b>OCP:</b> Open for extension (generic T), closed for modification
 *   <li><b>DIP:</b> Domain layer doesn't depend on infrastructure
 * </ul>
 *
 * @param <T> Payload type (must be serializable to JSON)
 * @see ADR-018 Strategy Pattern for ACL
 */
data class IntegrationEvent<T>(
    /**
     * Unique event identifier for tracing and deduplication. Uses UUID to ensure global uniqueness
     * across distributed systems.
     */
    val eventId: String,

    /**
     * Event type identifier (e.g., "NEXON_DATA_COLLECTED", "CHARACTER_UPDATED"). Used for event
     * routing and filtering.
     */
    val eventType: String,

    /**
     * Event creation timestamp in epoch milliseconds. Used for event ordering and latency
     * measurement.
     */
    val timestamp: Long,

    /** Actual event payload. Can be any domain object that needs to be transmitted through the
     * pipeline.
     */
    val payload: T
) {

  companion object {
    /**
     * Create a new event with auto-generated metadata.
     *
     * <p>This factory method encapsulates event creation logic:
     *
     * <ul>
     *   <li>eventId: UUID generation
     *   <li>timestamp: Current time in epoch millis
     * </ul>
     *
     * @param type Event type identifier
     * @param payload Event payload (domain object)
     * @return New IntegrationEvent instance with generated metadata
     */
    @JvmStatic
    fun <T> of(type: String, payload: T): IntegrationEvent<T> {
      return IntegrationEvent(
          eventId = UUID.randomUUID().toString(),
          eventType = type,
          timestamp = Instant.now().toEpochMilli(),
          payload = payload
      )
    }
  }
}
