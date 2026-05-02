package maple.expectation.application.service.expectation.queue;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.model.job.CalculationJob;
import maple.expectation.core.model.job.CalculationJobClaim;
import maple.expectation.core.model.job.CalculationJobStatus;
import maple.expectation.core.port.inbound.TaskReceipt;
import maple.expectation.core.port.out.CalculationJobPort;
import maple.expectation.core.port.out.PgmqPort;
import maple.expectation.core.port.out.QueueNames;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.pgmq.ExternalApiJobPayload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * V5 Direct Dispatch Queue — bypasses expectation_calc_high routing hop.
 *
 * <p>Controller directly creates a job and publishes ExternalApiJobPayload to external_api_queue,
 * eliminating the routing-only ExpectationCalcWorker.
 *
 * <p>Before: Controller → expectation_calc_high → ExpectationCalcWorker → external_api_queue After:
 * Controller → job creation + external_api_queue publish
 *
 * <p>Job-level dedup is handled by {@link CalculationJobPort#createOrFindActiveJob}.
 */
@Slf4j
@Component
public class ExpectationCalculationQueue {

  private final PgmqPort pgmqPort;
  private final CalculationJobPort jobPort;
  private final LogicExecutor executor;

  public ExpectationCalculationQueue(
      PgmqPort pgmqPort, CalculationJobPort jobPort, LogicExecutor executor) {
    this.pgmqPort = pgmqPort;
    this.jobPort = jobPort;
    this.executor = executor;
  }

  public boolean offer(ExpectationCalculationTask task) {
    TaskContext context = TaskContext.of("Queue", "Offer", task.getUserIgn());
    return executor.executeOrDefault(() -> enqueue(task).getQueued(), false, context);
  }

  @Transactional(value = "transactionManager", propagation = Propagation.REQUIRES_NEW)
  public TaskReceipt offerWithReceipt(ExpectationCalculationTask task) {
    TaskContext context = TaskContext.of("Queue", "OfferWithReceipt", task.getUserIgn());
    return executor.executeOrDefault(
        () -> enqueue(task), TaskReceipt.rejected(task.getUserIgn()), context);
  }

  public boolean addHighPriorityTask(String userIgn, boolean forceRecalculation) {
    return offer(ExpectationCalculationTask.highPriority(userIgn, forceRecalculation));
  }

  public boolean addLowPriorityTask(String userIgn) {
    return offer(ExpectationCalculationTask.lowPriority(userIgn));
  }

  // Legacy stubs — kept for backward compatibility with PriorityCalculationExecutor

  /**
   * @deprecated PGMQ workers handle message consumption
   */
  @Deprecated
  public ExpectationCalculationTask poll(QueuePriority priority) throws InterruptedException {
    throw new UnsupportedOperationException(
        "poll() no longer supported. Direct dispatch to external_api_queue.");
  }

  /**
   * @deprecated PGMQ workers handle message consumption
   */
  @Deprecated
  public ExpectationCalculationTask poll(QueuePriority priority, long timeoutMs) {
    throw new UnsupportedOperationException(
        "poll() no longer supported. Direct dispatch to external_api_queue.");
  }

  /**
   * @deprecated Queue no longer uses separate PGMQ queues for metrics
   */
  @Deprecated
  public int size() {
    return 0;
  }

  /**
   * @deprecated Queue no longer uses separate PGMQ queues for metrics
   */
  @Deprecated
  public int getHighPriorityCount() {
    return 0;
  }

  /**
   * @deprecated Queue no longer uses separate PGMQ queues for metrics
   */
  @Deprecated
  public int getLowPriorityCount() {
    return 0;
  }

  /**
   * @deprecated PGMQ workers handle archiving
   */
  @Deprecated
  public void complete(ExpectationCalculationTask task) {
    // No-op
  }

  /**
   * Direct dispatch: create job + publish to external_api_queue in one transaction.
   *
   * <p>Job-level dedup: {@link CalculationJobPort#createOrFindActiveJob} returns existing active
   * job if one exists for the same request key. Only newly created jobs are dispatched.
   */
  private TaskReceipt enqueue(ExpectationCalculationTask task) {
    CalculationJobClaim claim =
        jobPort.createOrFindActiveJob(null, task.getUserIgn(), task.getPresetNo());
    CalculationJob job = claim.getJob();

    if (claim.getCreated() && job.getStatus() == CalculationJobStatus.REQUESTED) {
      boolean transitioned =
          jobPort.transitionStatus(
              job.getJobId(), CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING);
      if (transitioned) {
        pgmqPort.send(
            QueueNames.EXTERNAL_API,
            new ExternalApiJobPayload(
                job.getJobId().toString(), task.getUserIgn(), task.getPresetNo()));
        log.debug(
            "[Queue] Job dispatched: jobId={}, userIgn={}, presetNo={}",
            job.getJobId(),
            task.getUserIgn(),
            task.getPresetNo());
      }
    } else {
      log.debug(
          "[Queue] Existing active job returned: jobId={}, status={}, userIgn={}",
          job.getJobId(),
          job.getStatus(),
          task.getUserIgn());
    }

    return new TaskReceipt(job.getJobId().toString(), task.getUserIgn(), true);
  }
}
