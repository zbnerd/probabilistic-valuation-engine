package maple.expectation.application.service.expectation.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * V5 CQRS: Calculation Completed Event Listener
 *
 * <h3>Purpose</h3>
 *
 * <p>Intercepts CalculationCompletedEvent and publishes it to PGMQ within the SAME transaction as
 * the V4 calculation.
 *
 * <h3>Transaction Phase: BEFORE_COMMIT</h3>
 *
 * <p>Uses <b>BEFORE_COMMIT</b> to ensure atomicity:
 *
 * <ul>
 *   <li>If V4 calculation fails → Transaction rolls back → Event is NOT published
 *   <li>If PGMQ publish fails → Transaction rolls back → V4 calculation is rolled back
 *   <li>Only when BOTH succeed → Transaction commits
 * </ul>
 *
 * <h3>Flow</h3>
 *
 * <pre>
 * ExpectationCalculationWorker (TX begins)
 *   → expectationService.calculateExpectation()
 *   → ApplicationEventPublisher.publishEvent(CalculationCompletedEvent)
 *   → @TransactionalEventListener(BEFORE_COMMIT) intercepts
 *   → TransactionalEventPublisher.publishToPgmq() [same TX, shared DB]
 *   → TX Commit (both V4 result AND PGMQ message committed atomically)
 * </pre>
 *
 * <h3>Section 12 Compliance</h3>
 *
 * <p>All exception handling delegated to TransactionalEventPublisher/LogicExecutor. NO try-catch
 * blocks in this listener.
 *
 * @see maple.expectation.core.event.CalculationCompletedEvent
 * @see TransactionalEventPublisher
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    prefix = "v5",
    name = "query-side-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class CalculationCompletedEventListener {

  private final TransactionalEventPublisher transactionalPublisher;

  /**
   * Handle calculation completed event BEFORE transaction commits.
   *
   * <p>This ensures the event is published to PGMQ in the SAME transaction as V4 calculation.
   *
   * <p><b>CRITICAL:</b> Using BEFORE_COMMIT instead of AFTER_COMMIT to prevent dual-write problem.
   *
   * @param event Calculation completed event
   */
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void handleCalculationCompleted(CalculationCompletedEvent event) {
    log.debug(
        "[EventListener] Intercepted CalculationCompletedEvent: taskId={}, userIgn={}, eventId={}",
        event.taskId(),
        event.userIgn(),
        event.eventId());

    // Delegate to TransactionalEventPublisher which publishes to PGMQ in the same transaction
    transactionalPublisher.publishCalculationCompleted(event);

    log.info(
        "[EventListener] Event published to PGMQ: taskId={}, userIgn={}",
        event.taskId(),
        event.userIgn());
  }

  /**
   * Optional async handler for post-commit processing (if needed).
   *
   * <p>This can be used for non-critical post-processing like metrics updates.
   *
   * <p>Note: This does NOT participate in the original transaction.
   */
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleAfterCommit(CalculationCompletedEvent event) {
    log.debug(
        "[EventListener-AFTER_COMMIT] Transaction committed for: taskId={}, userIgn={}",
        event.taskId(),
        event.userIgn());

    // Non-critical post-commit processing can go here
    // For example: metrics, notifications, etc.
  }
}
