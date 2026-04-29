package maple.expectation.application.worker;

import io.micrometer.core.instrument.Counter;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.expectation.EquipmentExpectationServiceV4;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationQueue;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationTask;
import maple.expectation.application.service.expectation.queue.QueuePriority;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: Calculation Worker - Processes queue tasks
 *
 * <h3>Flow</h3>
 *
 * <ol>
 *   <li>Poll task from ExpectationCalculationQueue
 *   <li>Calculate using V4 service (reuse existing logic)
 *   <li>Publish event to Redis Stream
 *   <li>Upsert to MongoDB view
 *   <li>Complete task in queue
 * </ol>
 *
 * <h3>Section 12 Compliance</h3>
 *
 * <p>All exception handling delegated to LogicExecutor/CheckedLogicExecutor.
 */
@Slf4j
@Component
public class ExpectationCalculationWorker implements Runnable {

  // ADR-080 Fix 2: Track active workers for startup verification
  private static final AtomicInteger ACTIVE_WORKERS = new AtomicInteger(0);

  /**
   * Get the current number of active workers (for monitoring/verification).
   *
   * @return active worker count
   */
  public static int getActiveWorkerCount() {
    return ACTIVE_WORKERS.get();
  }

  private final ExpectationCalculationQueue queue;
  private final EquipmentExpectationServiceV4 expectationService;
  private final LogicExecutor executor;
  private final CheckedLogicExecutor checkedExecutor;
  private final Counter processedCounter;
  private final Counter errorCounter;

  public ExpectationCalculationWorker(
      ExpectationCalculationQueue queue,
      EquipmentExpectationServiceV4 expectationService,
      LogicExecutor executor,
      @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedExecutor,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.queue = queue;
    this.expectationService = expectationService;
    this.executor = executor;
    this.checkedExecutor = checkedExecutor;
    this.processedCounter = meterRegistry.counter("calculation.worker.processed");
    this.errorCounter = meterRegistry.counter("calculation.worker.errors");
  }

  /**
   * Run the worker loop for the specified priority.
   *
   * <p>ADR-038 Fix: Each worker instance polls from its designated priority queue, ensuring
   * complete isolation between HIGH and LOW priority tasks.
   *
   * @param priority The priority queue this worker should poll from
   */
  public void runForPriority(QueuePriority priority) {
    // ADR-080 Fix 2: Track active worker count
    ACTIVE_WORKERS.incrementAndGet();
    log.info(
        "[V5-Worker] {} priority calculation worker started (active: {})",
        priority,
        ACTIVE_WORKERS.get());

    while (!Thread.currentThread().isInterrupted()) {
      processNextTaskWithRecovery(priority);
    }

    log.info("[V5-Worker] {} priority calculation worker stopped", priority);
  }

  @Override
  public void run() {
    // Legacy run method - defaults to LOW priority for backward compatibility
    runForPriority(QueuePriority.LOW);
  }

  /**
   * Process next task with recovery pattern (Section 12 compliant).
   *
   * <p>Uses CheckedLogicExecutor for queue.poll() which throws InterruptedException.
   *
   * <p>ADR-038 Fix: Polls from the specified priority queue.
   *
   * @param priority The priority queue to poll from
   */
  private void processNextTaskWithRecovery(QueuePriority priority) {
    ExpectationCalculationTask task = pollTaskOrNull(priority);

    if (task == null) {
      return;
    }

    TaskContext context = TaskContext.of("V5-Worker", "Calculate", task.getUserIgn());
    task.setStartedAt(Instant.now());

    executeCalculationWithFinally(task, context);
  }

  /** Process next task with recovery (legacy method for backward compatibility). */
  private void processNextTaskWithRecovery() {
    processNextTaskWithRecovery(QueuePriority.LOW);
  }

  /**
   * Poll task from queue, returning null on interruption (graceful shutdown).
   *
   * <p>ADR-038 Fix: Polls from the specified priority queue.
   *
   * @param priority The priority queue to poll from
   * @return task or null if interrupted
   */
  private ExpectationCalculationTask pollTaskOrNull(QueuePriority priority) {
    return executor.executeWithFallback(
        () -> queue.poll(priority),
        ex -> {
          if (ex instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            log.info("[V5-Worker] Worker interrupted, shutting down");
            throw new WorkerShutdownException((InterruptedException) ex);
          }
          throw ex instanceof RuntimeException ? (RuntimeException) ex : new RuntimeException(ex);
        },
        TaskContext.of("V5-Worker", "PollTask", priority.name()));
  }

  /** Poll task from queue (legacy method for backward compatibility). */
  private ExpectationCalculationTask pollTaskOrNull() {
    return pollTaskOrNull(QueuePriority.LOW);
  }

  /** Execute calculation with finally pattern ensuring task completion. */
  private void executeCalculationWithFinally(ExpectationCalculationTask task, TaskContext context) {
    executor.executeWithFinally(
        () -> {
          executeCalculation(task);
          return null; // Return null since executeWithFinally requires a return value
        },
        () -> queue.complete(task),
        context);
  }

  /** Execute calculation with error recovery. */
  private void executeCalculation(ExpectationCalculationTask task) {
    executor.executeOrCatch(
        () -> {
          EquipmentExpectationResponseV4 response =
              expectationService.calculateExpectation(
                  task.getUserIgn(), task.isForceRecalculation());

          processedCounter.increment();
          log.info(
              "[V5-Worker] Calculation completed: userIgn={}, taskId={}, cost={}, maxPreset={}",
              task.getUserIgn(),
              task.getTaskId(),
              response.getTotalExpectedCost(),
              response.getMaxPresetNo());
          return null;
        },
        e -> {
          errorCounter.increment();
          log.error("[V5-Worker] Calculation failed for: {}", task.getUserIgn(), e);
          return null;
        },
        TaskContext.of("V5-Worker", "ExecuteCalculation", task.getUserIgn()));
  }

  /** RuntimeException to signal graceful worker shutdown. */
  private static class WorkerShutdownException extends RuntimeException {
    WorkerShutdownException(InterruptedException cause) {
      super(cause);
    }
  }
}
