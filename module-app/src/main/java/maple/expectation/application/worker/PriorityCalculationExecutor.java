package maple.expectation.application.worker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationQueue;
import maple.expectation.application.service.expectation.queue.QueuePriority;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: Priority Executor - Manages calculation worker pool with Fast Lane isolation
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Worker pool lifecycle (start/shutdown)
 *   <li>Task submission to priority queue
 *   <li>Graceful shutdown with timeout
 * </ul>
 *
 * <h3>ADR-038 Fix: Complete Priority Isolation</h3>
 *
 * <p>Implemented separate pools and separate queues for HIGH and LOW priority tasks:
 *
 * <ul>
 *   <li><b>High Priority Pool (Fast Lane)</b>: Dedicated pool for user-initiated requests
 *   <li><b>Low Priority Pool (Background)</b>: Dedicated pool for batch/scheduled updates
 *   <li><b>Separate Queues</b>: Each pool polls from its own bounded queue
 * </ul>
 *
 * <h3>Task Submission Flow</h3>
 *
 * <ol>
 *   <li>Client submits task with priority (HIGH/LOW)
 *   <li>Task added to appropriate priority queue (HIGH or LOW)
 *   <li>Workers from appropriate pool poll from their designated queue and process
 *   <li>Results persisted to MySQL
 *   <li>Event published to Redis Stream
 * </ol>
 */
@Slf4j
@Component
public class PriorityCalculationExecutor {

  private final ExpectationCalculationQueue queue;
  private final ExpectationCalculationWorker worker;
  private final LogicExecutor executor;
  private final CheckedLogicExecutor checkedExecutor;
  private final int workerPoolSize;
  private final int shutdownTimeoutSeconds;
  private final int highPriorityWorkerRatio; // P1 FIX: Ratio for HIGH priority pool
  private final long verifyDelayMs; // ADR-080: Configurable worker startup verification delay

  // P1 FIX: Separate pools for HIGH and LOW priority to prevent starvation
  private ExecutorService highPriorityPool; // Fast Lane for user requests
  private ExecutorService lowPriorityPool; // Background pool for batch jobs
  private volatile boolean running = false;

  public PriorityCalculationExecutor(
      ExpectationCalculationQueue queue,
      ExpectationCalculationWorker worker,
      LogicExecutor executor,
      @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedExecutor,
      @Value("${app.v5.worker-pool-size:4}") int workerPoolSize,
      @Value("${app.v5.shutdown-timeout-seconds:30}") int shutdownTimeoutSeconds,
      @Value("${app.v5.high-priority-worker-ratio:0.5}") double highPriorityWorkerRatio,
      @Value("${app.v5.worker.startup.verifyDelayMs:100}") long verifyDelayMs) {
    this.queue = queue;
    this.worker = worker;
    this.executor = executor;
    this.checkedExecutor = checkedExecutor;
    this.workerPoolSize = workerPoolSize;
    this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    this.highPriorityWorkerRatio = (int) (highPriorityWorkerRatio * 100); // 50% by default
    this.verifyDelayMs = verifyDelayMs;
  }

  /**
   * Start worker pools with complete priority isolation.
   *
   * <p>ADR-038 Fix: Each pool uses workers that poll from their designated priority queue only.
   * This ensures HIGH priority workers never process LOW priority tasks, and vice versa.
   */
  public void start() {
    if (running) {
      log.warn("[V5-Executor] Already running");
      return;
    }

    TaskContext context = TaskContext.of("V5-Executor", "Start");

    executor.executeVoidJava(
        () -> {
          // Calculate pool sizes (ensure at least 1 worker per pool)
          int highPriorityCount =
              Math.max(1, (int) Math.ceil(workerPoolSize * highPriorityWorkerRatio / 100.0));
          int lowPriorityCount = Math.max(1, workerPoolSize - highPriorityCount);

          // ADR-038 Fix: Create separate pools for complete isolation
          highPriorityPool = Executors.newFixedThreadPool(highPriorityCount);
          lowPriorityPool = Executors.newFixedThreadPool(lowPriorityCount);

          // ADR-038 Fix: Submit priority-aware workers to each pool
          // HIGH priority workers only poll from HIGH priority queue
          for (int i = 0; i < highPriorityCount; i++) {
            highPriorityPool.submit(() -> worker.runForPriority(QueuePriority.HIGH));
          }

          // LOW priority workers only poll from LOW priority queue
          for (int i = 0; i < lowPriorityCount; i++) {
            lowPriorityPool.submit(() -> worker.runForPriority(QueuePriority.LOW));
          }

          // ADR-080 Fix 1: Verify workers have started (non-blocking)
          verifyWorkersStarted(workerPoolSize);

          running = true;
          log.info(
              "[V5-Executor] Started with {} total workers (HIGH: {}, LOW: {})",
              workerPoolSize,
              highPriorityCount,
              lowPriorityCount);
        },
        context);
  }

  /** Stop worker pools gracefully */
  public void stop() {
    if (!running) {
      log.warn("[V5-Executor] Not running");
      return;
    }

    TaskContext context = TaskContext.of("V5-Executor", "Stop");

    executor.executeVoidJava(
        () -> {
          running = false;
          highPriorityPool.shutdown();
          lowPriorityPool.shutdown();

          awaitTerminationWithRecovery();
        },
        context);
  }

  /** Wait for pool termination with interrupt recovery (Section 12 compliant) */
  private void awaitTerminationWithRecovery() {
    checkedExecutor.executeUncheckedVoid(
        () -> {
          try {
            if (!awaitTermination(highPriorityPool, lowPriorityPool)) {
              log.warn("[V5-Executor] Shutdown timeout, forcing termination");
              highPriorityPool.shutdownNow();
              lowPriorityPool.shutdownNow();
            }
            log.info("[V5-Executor] Stopped gracefully");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            highPriorityPool.shutdownNow();
            lowPriorityPool.shutdownNow();
            log.warn("[V5-Executor] Shutdown interrupted");
            throw new ShutdownInterruptedException(e);
          }
        },
        TaskContext.of("V5-Executor", "AwaitTermination"),
        ex -> new IllegalStateException("Unexpected exception during shutdown", ex));
  }

  /** RuntimeException wrapper for InterruptedException during shutdown */
  private static class ShutdownInterruptedException extends RuntimeException {
    ShutdownInterruptedException(InterruptedException cause) {
      super(cause);
    }
  }

  /** Wait for both pools to terminate with timeout */
  private boolean awaitTermination(ExecutorService pool1, ExecutorService pool2)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + shutdownTimeoutSeconds * 1000L;

    // Wait for pool1
    long remaining1 = Math.max(0, deadline - System.currentTimeMillis());
    boolean terminated1 = pool1.awaitTermination(remaining1, TimeUnit.MILLISECONDS);

    // Wait for pool2
    long remaining2 = Math.max(0, deadline - System.currentTimeMillis());
    boolean terminated2 = pool2.awaitTermination(remaining2, TimeUnit.MILLISECONDS);

    return terminated1 && terminated2;
  }

  /**
   * Submit high priority task (user-initiated request)
   *
   * @param userIgn character IGN
   * @param forceRecalculation force recalculation flag
   * @return task ID if queued, null if rejected
   */
  public String submitHighPriority(String userIgn, boolean forceRecalculation) {
    TaskContext context = TaskContext.of("V5-Executor", "SubmitHigh", userIgn);

    return executor.executeOrDefault(
        () -> {
          boolean added = queue.addHighPriorityTask(userIgn, forceRecalculation);
          if (added) {
            log.info("[V5-Executor] HIGH priority task queued: userIgn={}", userIgn);
            return "queued";
          }
          log.warn("[V5-Executor] HIGH priority task rejected (backpressure): userIgn={}", userIgn);
          return null;
        },
        null,
        context);
  }

  /**
   * Submit low priority task (batch/scheduled update)
   *
   * @param userIgn character IGN
   * @return task ID if queued, null if rejected
   */
  public String submitLowPriority(String userIgn) {
    TaskContext context = TaskContext.of("V5-Executor", "SubmitLow", userIgn);

    return executor.executeOrDefault(
        () -> {
          boolean added = queue.addLowPriorityTask(userIgn);
          if (added) {
            log.debug("[V5-Executor] LOW priority task queued: userIgn={}", userIgn);
            return "queued";
          }
          log.debug("[V5-Executor] LOW priority task rejected (backpressure): userIgn={}", userIgn);
          return null;
        },
        null,
        context);
  }

  /** Get current queue size */
  public int getQueueSize() {
    return queue.size();
  }

  /** Get high priority task count */
  public int getHighPriorityCount() {
    return queue.getHighPriorityCount();
  }

  /** Get low priority task count */
  public int getLowPriorityCount() {
    return queue.getLowPriorityCount();
  }

  /** Check if executor is running */
  public boolean isRunning() {
    return running;
  }

  /**
   * ADR-080 Fix 1: Get the current number of active workers.
   *
   * <p>This provides visibility into worker pool health for monitoring and startup verification.
   *
   * @return active worker count from ExpectationCalculationWorker
   */
  public int getActiveWorkerCount() {
    return ExpectationCalculationWorker.getActiveWorkerCount();
  }

  /**
   * ADR-080 Fix 1: Verify workers have started after submission.
   *
   * <p>Non-blocking verification that logs a warning if not all workers are active. This helps
   * detect startup race conditions without blocking initialization.
   *
   * @param expectedCount expected number of active workers
   */
  private void verifyWorkersStarted(int expectedCount) {
    executor.executeVoidJava(
        () -> {
          // ADR-080: Configurable delay to allow workers to enter run loop
          try {
            TimeUnit.MILLISECONDS.sleep(verifyDelayMs);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }

          int activeWorkers = getActiveWorkerCount();
          if (activeWorkers < expectedCount) {
            log.warn(
                "[V5-Executor] Only {}/{} workers active after startup (some may still be starting)",
                activeWorkers,
                expectedCount);
          } else {
            log.info("[V5-Executor] All {} workers verified active", activeWorkers);
          }
        },
        TaskContext.of("V5-Executor", "VerifyWorkersStarted"));
  }
}
