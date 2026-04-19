package maple.expectation.application.service.expectation.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.pgmq.PgmqClient;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: Transactional Event Publisher (PGMQ Direct)
 *
 * <h3>Purpose</h3>
 *
 * <p>Publishes events directly to PGMQ within the SAME transaction as V4 calculation. This replaces
 * the EventOutbox bridge pattern with PGMQ's native same-transaction publishing:
 *
 * <pre>
 * Before (Phase 0): Service → EventOutbox.save() → Scheduler(10s) → PgmqStreamPublisher
 * After  (Phase 1): Service → pgmqClient.send() [same TX]
 * </pre>
 *
 * <h3>Dual-Write Prevention</h3>
 *
 * <p>Since PGMQ shares the same PostgreSQL database, {@code pgmqClient.send()} participates in the
 * caller's {@code @Transactional}. If V4 calculation rolls back, the PGMQ message is also rolled
 * back.
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

  private static final String QUEUE_NAME = "character-sync";
  private static final String EVENT_TYPE = "EXPECTATION_CALCULATED";

  private final PgmqClient pgmqClient;
  private final LogicExecutor executor;
  private final ObjectMapper objectMapper;

  /**
   * Publish calculation completed event directly to PGMQ.
   *
   * <p>This method runs in the SAME transaction as the V4 calculation. The PGMQ message is sent
   * before the transaction commits — if TX rolls back, the message is also rolled back.
   *
   * @param event Calculation completed event
   */
  public void publishCalculationCompleted(CalculationCompletedEvent event) {
    TaskContext context = TaskContext.of("TransactionalPublisher", "Publish", event.taskId());

    executor.executeVoidJava(() -> publishToPgmq(event), context);
  }

  private void publishToPgmq(CalculationCompletedEvent event) {
    executor.executeWithFallback(
        () -> {
          String payload = serializePayload(event.response());
          EventMessage message =
              new EventMessage(event.eventId(), EVENT_TYPE, payload, Instant.now().toEpochMilli());

          pgmqClient.send(QUEUE_NAME, message);

          log.info(
              "[TransactionalPublisher] Published event to PGMQ: taskId={}, userIgn={}, eventId={}",
              event.taskId(),
              event.userIgn(),
              event.eventId());

          return null;
        },
        (error) -> {
          log.error(
              "[TransactionalPublisher] Failed to publish event: taskId={}, userIgn={}",
              event.taskId(),
              event.userIgn(),
              error);
          throw new EventPublishException("Failed to publish event to PGMQ", error);
        },
        TaskContext.of("TransactionalPublisher", "PublishToPgmq", event.taskId()));
  }

  private String serializePayload(
      maple.expectation.web.dto.v4.EquipmentExpectationResponseV4 response) {
    return executor.executeOrDefault(
        () -> objectMapper.writeValueAsString(response),
        "{}",
        TaskContext.of("TransactionalPublisher", "Serialize", response.getUserIgn()));
  }

  /** PGMQ message envelope matching consumer expectations. */
  public record EventMessage(String eventId, String eventType, String payload, long timestamp) {}

  /** Exception thrown when PGMQ publish fails, causing TX rollback. */
  public static class EventPublishException extends RuntimeException {
    public EventPublishException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
