package maple.expectation.service.v5.queue;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: Priority Queue for Expectation Calculation
 *
 * <h3>Priority Strategy</h3>
 *
 * <ul>
 *   <li>HIGH: User-initiated requests (immediate processing)
 *   <li>LOW: Batch/scheduled updates (background processing)
 * </ul>
 *
 * <h3>ADR-038 Fix: Separate Bounded Queues</h3>
 *
 * <p>Prior to this fix, the queue used a single {@code PriorityBlockingQueue} which:
 *
 * <ul>
 *   <li>Was unbounded (memory exhaustion risk)
 *   <li>Allowed LOW priority tasks to be picked up by HIGH priority workers (isolation failure)
 * </ul>
 *
 * <p>Now uses separate {@link LinkedBlockingQueue} instances per priority with bounded capacity:
 *
 * <ul>
 *   <li>highPriorityQueue: capacity 1,000 (bounded, protects user requests)
 *   <li>lowPriorityQueue: capacity 10,000 (bounded, protects memory)
 *   <li>Complete worker isolation: HIGH workers only process HIGH tasks
 * </ul>
 *
 * <h3>Backpressure</h3>
 *
 * <p>When HIGH priority queue is full, LOW priority tasks are rejected to protect user experience.
 */
@Slf4j
@Component
public class PriorityCalculationQueue {

  private static final int MAX_QUEUE_SIZE = 10_000;
  private static final int HIGH_PRIORITY_CAPACITY = 1_000;

  // ADR-038 Fix: Separate bounded queues per priority
  private final LinkedBlockingQueue<ExpectationCalculationTask> highPriorityQueue;
  private final LinkedBlockingQueue<ExpectationCalculationTask> lowPriorityQueue;
  private final LogicExecutor executor;

  public PriorityCalculationQueue(LogicExecutor executor) {
    this.executor = executor;
    // ADR-038 Fix: Bounded queues prevent memory exhaustion
    this.highPriorityQueue = new LinkedBlockingQueue<>(HIGH_PRIORITY_CAPACITY);
    this.lowPriorityQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
  }

  /**
   * Offer task to appropriate priority queue with backpressure control.
   *
   * <p>ADR-038 Fix: Routes tasks to separate queues based on priority. When HIGH priority queue is
   * full, LOW priority tasks are rejected to protect user experience.
   *
   * @return true if queued, false if rejected (backpressure)
   */
  public boolean offer(ExpectationCalculationTask task) {
    TaskContext context = TaskContext.of("Queue", "Offer", task.getUserIgn());

    return executor.executeOrDefault(
        () -> {
          boolean added =
              switch (task.getPriority()) {
                case HIGH -> highPriorityQueue.offer(task);
                case LOW -> {
                  // ADR-038 Fix: If high priority queue is full, reject low priority
                  if (highPriorityQueue.remainingCapacity() == 0) {
                    log.warn(
                        "[Queue] High priority queue full, rejecting LOW priority: {}",
                        task.getUserIgn());
                    yield false;
                  }
                  yield lowPriorityQueue.offer(task);
                }
              };

          if (!added) {
            log.warn(
                "[Queue] Queue full, rejecting: priority={}, userIgn={}",
                task.getPriority(),
                task.getUserIgn());
          }
          return added;
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
   * Poll next task from the specified priority queue (blocking).
   *
   * <p>ADR-038 Fix: Each worker polls from its designated queue, ensuring complete isolation.
   *
   * @param priority The priority queue to poll from
   * @return task from the specified queue
   * @throws InterruptedException if interrupted while waiting
   */
  public ExpectationCalculationTask poll(QueuePriority priority) throws InterruptedException {
    return switch (priority) {
      case HIGH -> highPriorityQueue.take();
      case LOW -> lowPriorityQueue.take();
    };
  }

  /**
   * Poll next task with timeout (non-blocking when timeout expires).
   *
   * <p>Uses LogicExecutor.executeOrDefault() for Section 12 compliance. InterruptedException is
   * caught at IO boundary, thread is restored, and null is returned.
   *
   * <p>ADR-038 Fix: Polls from the specified priority queue only.
   *
   * @param priority The priority queue to poll from
   * @param timeoutMs timeout in milliseconds
   * @return task or null if timeout or interrupted
   */
  public ExpectationCalculationTask poll(QueuePriority priority, long timeoutMs) {
    return executor.executeOrDefault(
        () -> {
          try {
            return switch (priority) {
              case HIGH -> highPriorityQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
              case LOW -> lowPriorityQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            };
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[Queue] Poll interrupted for priority {}, returning null", priority);
            return null;
          }
        },
        null,
        TaskContext.of("Queue", "PollWithTimeout", priority.name()));
  }

  /** Get total queue size (both priorities). */
  public int size() {
    return highPriorityQueue.size() + lowPriorityQueue.size();
  }

  /** Get high priority queue size. */
  public int getHighPriorityCount() {
    return highPriorityQueue.size();
  }

  /** Get low priority queue size. */
  public int getLowPriorityCount() {
    return lowPriorityQueue.size();
  }

  /**
   * Mark task as completed.
   *
   * <p>ADR-038 Fix: Since we now use separate queues, this method is kept for compatibility but
   * doesn't need to decrement counters (the queues themselves track size).
   */
  public void complete(ExpectationCalculationTask task) {
    task.setCompletedAt(java.time.Instant.now());
  }
}
