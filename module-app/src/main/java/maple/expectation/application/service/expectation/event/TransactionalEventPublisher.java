package maple.expectation.application.service.expectation.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.event.CalculationCompletedEvent;
import maple.expectation.domain.v2.EventOutbox;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.repository.EventOutboxRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * V5 CQRS: Transactional Event Publisher
 *
 * <h3>Purpose</h3>
 *
 * <p>Saves events to EventOutbox within the SAME transaction as V4 calculation. This prevents the
 * dual-write problem:
 *
 * <ul>
 *   <li><b>Event Loss:</b> MySQL commits → Server crashes before XADD → Read Model drift
 *   <li><b>Phantom Events:</b> XADD succeeds → MySQL rolls back → Invalid data
 * </ul>
 *
 * <h3>Transaction Boundary</h3>
 *
 * <p>Uses @Transactional to ensure EventOutbox.save() participates in the V4 calculation
 * transaction. If V4 calculation fails, EventOutbox insert is rolled back automatically.
 *
 * <h3>Flow</h3>
 *
 * <pre>
 * ExpectationCalculationWorker (TX)
 *   → expectationService.calculateExpectation()
 *   → publishCalculationCompleted() [TX continues]
 *   → EventOutbox.save() [same TX]
 *   → TX Commit
 *   → EventOutboxProcessor (NEW TX) → Redis Stream
 * </pre>
 *
 * <h3>Section 12 Compliance</h3>
 *
 * <p>All exception handling delegated to LogicExecutor. NO try-catch blocks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    prefix = "v5",
    name = "query-side-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class TransactionalEventPublisher {

  private static final String TARGET_STREAM = "character-sync";
  private static final String EVENT_TYPE_CALCULATED = "EXPECTATION_CALCULATED";

  private final EventOutboxRepository eventOutboxRepository;
  private final LogicExecutor executor;
  private final ObjectMapper objectMapper;

  /**
   * Publish calculation completed event to EventOutbox.
   *
   * <p>This method runs in the SAME transaction as the V4 calculation. The event is saved to
   * EventOutbox before the transaction commits.
   *
   * @param event Calculation completed event
   */
  @Transactional
  public void publishCalculationCompleted(CalculationCompletedEvent event) {
    TaskContext context = TaskContext.of("TransactionalPublisher", "Publish", event.taskId());

    executor.executeVoidJava(() -> saveToOutbox(event), context);
  }

  /**
   * Save event to EventOutbox within the current transaction.
   *
   * <p>Uses LogicExecutor.executeWithRecovery() for Section 12 compliance.
   */
  private void saveToOutbox(CalculationCompletedEvent event) {
    executor.executeWithRecovery(
        () -> {
          String payload = serializePayload(event.response());
          String eventId = event.eventId();

          EventOutbox outbox =
              EventOutbox.create(eventId, EVENT_TYPE_CALCULATED, payload, TARGET_STREAM);

          eventOutboxRepository.save(outbox);

          log.info(
              "[TransactionalPublisher] Saved event to outbox: taskId={}, userIgn={}, eventId={}",
              event.taskId(),
              event.userIgn(),
              eventId);

          return null;
        },
        (error) -> {
          log.error(
              "[TransactionalPublisher] Failed to save event to outbox: taskId={}, userIgn={}",
              event.taskId(),
              event.userIgn(),
              error);
          throw new EventOutboxSaveException("Failed to save event to outbox", error);
        },
        TaskContext.of("TransactionalPublisher", "SaveOutbox", event.taskId()));
  }

  /**
   * Serialize V4 response to JSON.
   *
   * <p>Uses LogicExecutor.executeOrDefault() for Section 12 compliance.
   */
  private String serializePayload(
      maple.expectation.web.dto.v4.EquipmentExpectationResponseV4 response) {
    return executor.executeOrDefault(
        () -> objectMapper.writeValueAsString(response),
        "{}",
        TaskContext.of("TransactionalPublisher", "Serialize", response.getUserIgn()));
  }

  /**
   * Exception thrown when EventOutbox save fails.
   *
   * <p>This causes the entire V4 calculation transaction to roll back, ensuring no orphaned
   * calculation results exist.
   */
  public static class EventOutboxSaveException extends RuntimeException {
    public EventOutboxSaveException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
