package maple.expectation.application.service.expectation.queue;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.pgmq.ExpectationCalcMessage;
import maple.expectation.infrastructure.pgmq.PgmqClient;
import maple.expectation.infrastructure.worker.ExpectationCalcLowWorker;
import maple.expectation.infrastructure.worker.ExpectationCalcWorker;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: Priority Queue for Expectation Calculation (Issue #634 PGMQ migration)
 *
 * <h3>PGMQ Migration</h3>
 *
 * <p>Replaced in-memory {@code LinkedBlockingQueue} with PGMQ-backed durable queues.
 * Message consumption is handled by {@link ExpectationCalcWorker} (HIGH) and
 * {@link ExpectationCalcLowWorker} (LOW) via the {@code PgmqWorker} abstraction.
 *
 * <h3>Priority Strategy</h3>
 *
 * <ul>
 *   <li>HIGH: User-initiated requests (immediate processing)
 *   <li>LOW: Batch/scheduled updates (background processing)
 * </ul>
 *
 * <h3>Backpressure</h3>
 *
 * <p>When a queue exceeds its capacity limit, new tasks are rejected.
 */
@Slf4j
@Component
public class ExpectationCalculationQueue {

  private static final int MAX_QUEUE_SIZE = 10_000;
  private static final int HIGH_PRIORITY_CAPACITY = 1_000;

  private final PgmqClient pgmqClient;
  private final LogicExecutor executor;

  public ExpectationCalculationQueue(PgmqClient pgmqClient, LogicExecutor executor) {
    this.pgmqClient = pgmqClient;
    this.executor = executor;
  }

  /**
   * Offer task to appropriate PGMQ queue with backpressure control.
   *
   * <p>Routes tasks to separate PGMQ queues based on priority. Backpressure is applied
   * when the queue exceeds its capacity limit.
   *
   * @return true if queued, false if rejected (backpressure)
   */
  public boolean offer(ExpectationCalculationTask task) {
    TaskContext context = TaskContext.of("Queue", "Offer", task.getUserIgn());

    return executor.executeOrDefault(
        () -> {
          String queueName = resolveQueueName(task.getPriority());
          int maxSize = resolveMaxSize(task.getPriority());

          long queueLength = pgmqClient.queueLength(queueName);
          if (queueLength >= maxSize) {
            log.warn(
                "[Queue] Queue full, rejecting: priority={}, userIgn={}",
                task.getPriority(),
                task.getUserIgn());
            return false;
          }

          ExpectationCalcMessage message =
              new ExpectationCalcMessage(
                  task.getUserIgn(), task.isForceRecalculation());
          pgmqClient.send(queueName, message);

          log.debug(
              "[Queue] Task queued: priority={}, userIgn={}",
              task.getPriority(),
              task.getUserIgn());
          return true;
        },
        false,
        context);
  }

  /**
   * Add HIGH priority task (user-initiated request).
   *
   * @return true if queued, false if rejected (backpressure)
   */
  public boolean addHighPriorityTask(String userIgn, boolean forceRecalculation) {
    return offer(ExpectationCalculationTask.highPriority(userIgn, forceRecalculation));
  }

  /**
   * Add LOW priority task (batch/scheduled update).
   *
   * @return true if queued, false if rejected (backpressure)
   */
  public boolean addLowPriorityTask(String userIgn) {
    return offer(ExpectationCalculationTask.lowPriority(userIgn));
  }

  /**
   * Poll next task from the specified priority queue.
   *
   * <p>DEPRECATED: PGMQ workers handle message consumption. This method throws
   * UnsupportedOperationException.
   *
   * @throws UnsupportedOperationException always - PGMQ workers handle consumption
   */
  public ExpectationCalculationTask poll(QueuePriority priority) throws InterruptedException {
    throw new UnsupportedOperationException(
        "poll() is no longer supported. PGMQ workers (ExpectationCalcWorker, ExpectationCalcLowWorker) handle message consumption.");
  }

  /**
   * Poll next task with timeout.
   *
   * <p>DEPRECATED: PGMQ workers handle message consumption. This method throws
   * UnsupportedOperationException.
   *
   * @throws UnsupportedOperationException always - PGMQ workers handle consumption
   */
  public ExpectationCalculationTask poll(QueuePriority priority, long timeoutMs) {
    throw new UnsupportedOperationException(
        "poll() is no longer supported. PGMQ workers (ExpectationCalcWorker, ExpectationCalcLowWorker) handle message consumption.");
  }

  /** Get total queue size (both priorities) from PGMQ. */
  public int size() {
    return getHighPriorityCount() + getLowPriorityCount();
  }

  /** Get high priority queue size from PGMQ. */
  public int getHighPriorityCount() {
    TaskContext context = TaskContext.of("Queue", "HighPriorityCount", ExpectationCalcWorker.QUEUE_NAME);
    return executor.executeOrDefault(
        () -> Math.toIntExact(pgmqClient.queueLength(ExpectationCalcWorker.QUEUE_NAME)),
        0,
        context);
  }

  /** Get low priority queue size from PGMQ. */
  public int getLowPriorityCount() {
    TaskContext context = TaskContext.of("Queue", "LowPriorityCount", ExpectationCalcLowWorker.QUEUE_NAME);
    return executor.executeOrDefault(
        () -> Math.toIntExact(pgmqClient.queueLength(ExpectationCalcLowWorker.QUEUE_NAME)),
        0,
        context);
  }

  /**
   * Mark task as completed.
   *
   * <p>DEPRECATED: PGMQ workers handle archiving on success. This method is a no-op.
   */
  public void complete(ExpectationCalculationTask task) {
    // No-op: PGMQ workers handle archiving on successful processing
  }

  private String resolveQueueName(QueuePriority priority) {
    return switch (priority) {
      case HIGH -> ExpectationCalcWorker.QUEUE_NAME;
      case LOW -> ExpectationCalcLowWorker.QUEUE_NAME;
    };
  }

  private int resolveMaxSize(QueuePriority priority) {
    return switch (priority) {
      case HIGH -> HIGH_PRIORITY_CAPACITY;
      case LOW -> MAX_QUEUE_SIZE;
    };
  }
}
