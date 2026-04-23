package maple.expectation.application.service.expectation.event;

import java.time.Instant;
import java.util.UUID;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4;

/**
 * V5 CQRS: Event fired when V4 calculation completes
 *
 * <h3>Purpose</h3>
 *
 * <ul>
 *   <li>Signals completion of expectation calculation
 *   <li>Carries calculation result for EventOutbox persistence
 *   <li>Enables transactional event publishing via @TransactionalEventListener
 * </ul>
 *
 * <h3>Flow</h3>
 *
 * <pre>
 * ExpectationCalculationWorker.calculate()
 *   → CalculationCompletedEvent published
 *   → CalculationCompletedEventListener (BEFORE_COMMIT)
 *   → EventOutbox.save() (same transaction)
 *   → Transaction commits
 *   → EventOutboxProcessor (new transaction)
 *   → Redis Stream → MongoDB
 * </pre>
 *
 * @param taskId Unique task identifier
 * @param userIgn User in-game name
 * @param response Full calculation response
 * @param calculatedAt Calculation timestamp
 * @param eventId Unique event ID for idempotency
 */
public record CalculationCompletedEvent(
    String taskId,
    String userIgn,
    EquipmentExpectationResponseV4 response,
    Instant calculatedAt,
    String eventId) {
  /**
   * Factory method to create event with auto-generated UUID
   *
   * @param taskId Task identifier
   * @param userIgn User in-game name
   * @param response Calculation response
   * @return CalculationCompletedEvent with generated eventId
   */
  public static CalculationCompletedEvent of(
      String taskId, String userIgn, EquipmentExpectationResponseV4 response) {
    return new CalculationCompletedEvent(
        taskId, userIgn, response, Instant.now(), UUID.randomUUID().toString());
  }

  /** Default constructor for Spring's event system */
  public CalculationCompletedEvent {
    if (eventId == null) {
      eventId = UUID.randomUUID().toString();
    }
  }
}
