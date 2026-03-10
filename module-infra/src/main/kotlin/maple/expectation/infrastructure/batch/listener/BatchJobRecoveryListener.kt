package maple.expectation.infrastructure.batch.listener

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Spring Batch Job Recovery Listener (P2-19)
 *
 * <h3>Purpose</h3>
 *
 * Tracks failed batch job executions and stores metadata for recovery.
 * Enables automatic restart of failed jobs by the BatchJobRecoveryScheduler.
 *
 * <h3>Functionality</h3>
 *
 * <ul>
 *   <li>Monitors job execution status via JobExecutionListener
 *   <li>Stores failed job metadata (jobInstanceId, timestamp, exit status)
 *   <li>Exposes failure count metrics via Micrometer
 *   <li>Thread-safe tracking using ConcurrentHashMap
 * </ul>
 *
 * <h3>P2-19 Requirements</h3>
 *
 * <ul>
 *   <li>JobRegistry integration - tracks job names for lookup
 *   <li>JobExecutionListener - handles beforeJob/afterJob callbacks
 *   <li>Failure metadata storage - enables recovery scheduling
 * </ul>
 *
 * <h3>CLAUDE.md Compliance</h3>
 *
 * <ul>
 *   <li>Section 12: LogicExecutor pattern for exception handling
 *   <li>Section 15: Lambda limit - extracted private methods
 *   <li>Stateless: Failure metadata is thread-safe but not persisted (handled by scheduler)
 * </ul>
 *
 * @see maple.expectation.infrastructure.batch.scheduler.BatchJobRecoveryScheduler
 */
@Component
class BatchJobRecoveryListener(
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry
) : JobExecutionListener {

  /** Track failed job executions - key: jobInstanceId, value: failure metadata */
  private val failedJobs = ConcurrentHashMap<Long, JobFailureMetadata>()

  /** Failure counter per job name */
  private val failureCounters = ConcurrentHashMap<String, Counter>()

  /**
   * Before job callback - log job start
   */
  override fun beforeJob(jobExecution: JobExecution) {
    val context = TaskContext.of("BatchRecovery", "beforeJob")

    executor.executeVoidJava({
      val jobName = jobExecution.jobInstance.jobName
      logJobStart(jobName, jobExecution.jobInstance.id)
    }, context)
  }

  /**
   * After job callback - track failures
   *
   * <p>If job failed, store metadata for recovery and increment metrics.
   */
  override fun afterJob(jobExecution: JobExecution) {
    val context = TaskContext.of("BatchRecovery", "afterJob")

    executor.executeVoidJava({
      val jobName = jobExecution.jobInstance.jobName
      val status = jobExecution.status

      if (status.isUnsuccessful && status.name != "STOPPED") {
        handleJobFailure(jobExecution, jobName)
      } else {
        logJobSuccess(jobName, jobExecution.jobInstance.id)
      }
    }, context)
  }

  /** Get failed job metadata for recovery */
  fun getFailedJobs(): Map<Long, JobFailureMetadata> = failedJobs.toMap()

  /** Remove job from failed tracking (after recovery attempt) */
  fun removeFailedJob(jobInstanceId: Long) {
    failedJobs.remove(jobInstanceId)
  }

  /** Get failure count for a specific job */
  fun getFailureCount(jobName: String): Long {
    return failureCounters[jobName]?.count()?.toLong() ?: 0L
  }

  // ========== Private Methods ==========

  /** Handle job failure - store metadata and increment metrics */
  private fun handleJobFailure(jobExecution: JobExecution, jobName: String) {
    val metadata = JobFailureMetadata(
        jobInstanceId = jobExecution.jobInstance.id,
        jobName = jobName,
        jobExecutionId = jobExecution.id,
        timestamp = Instant.now(),
        exitStatus = jobExecution.exitStatus.exitCode,
        failureExceptions = jobExecution.allFailureExceptions.map { it.message ?: "Unknown" }
    )

    failedJobs[jobExecution.jobInstance.id] = metadata

    // Increment failure counter
    val counter = failureCounters.computeIfAbsent(jobName) {
      Counter.builder("batch.job.failed.count")
          .tag("job_name", jobName)
          .description("Number of failed batch job executions")
          .register(meterRegistry)
    }
    counter.increment()

    logJobFailure(jobName, metadata)
  }

  /** Log job start */
  private fun logJobStart(jobName: String, jobInstanceId: Long) {
    log.debug("[BatchRecovery] Job started: {} (instanceId: {})", jobName, jobInstanceId)
  }

  /** Log job success */
  private fun logJobSuccess(jobName: String, jobInstanceId: Long) {
    log.info("[BatchRecovery] Job completed successfully: {} (instanceId: {})", jobName, jobInstanceId)
  }

  /** Log job failure with details */
  private fun logJobFailure(jobName: String, metadata: JobFailureMetadata) {
    log.error(
        "[BatchRecovery] Job failed: {} (instanceId: {}, executionId: {}, exitStatus: {}, exceptions: {})",
        jobName,
        metadata.jobInstanceId,
        metadata.jobExecutionId,
        metadata.exitStatus,
        metadata.failureExceptions
    )
  }

  companion object {
    private val log = LoggerFactory.getLogger(BatchJobRecoveryListener::class.java)
  }
}

/**
 * Job failure metadata for recovery
 *
 * @property jobInstanceId Job instance ID (unique per job run)
 * @property jobName Job name (e.g., "equipmentRefreshJob")
 * @property jobExecutionId Job execution ID
 * @property timestamp Failure timestamp
 * @property exitStatus Exit status code
 * @property failureExceptions List of failure exception messages
 */
data class JobFailureMetadata(
    val jobInstanceId: Long,
    val jobName: String,
    val jobExecutionId: Long,
    val timestamp: Instant,
    val exitStatus: String?,
    val failureExceptions: List<String>
)
