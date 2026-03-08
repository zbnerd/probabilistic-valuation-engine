package maple.expectation.infrastructure.batch.scheduler

import maple.expectation.error.exception.DistributedLockException
import maple.expectation.infrastructure.batch.listener.BatchJobRecoveryListener
import maple.expectation.infrastructure.batch.listener.JobFailureMetadata
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lock.LockStrategy
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Spring Batch Job Recovery Scheduler (P2-19)
 *
 * <h3>Purpose</h3>
 *
 * Automatically recovers failed batch jobs by querying for FAILED executions
 * and restarting them with exponential backoff.
 *
 * <h3>Recovery Strategy</h3>
 *
 * <ul>
 *   <li>Runs every 60 seconds to check for failed jobs
 *   <li>Uses JobExplorer to find recent FAILED executions (last 24 hours)
 *   <li>Implements exponential backoff: 1min → 2min → 4min → 8min → 16min → 30min (max)
 *   <li>Max retry limit: 5 attempts per job instance
 *   <li>Distributed lock prevents concurrent recovery across instances
 * </ul>
 *
 * <h3>Exponential Backoff Calculation</h3>
 *
 * <pre>
 * Attempt 1: wait 1 min (backoff level 0)
 * Attempt 2: wait 2 min (backoff level 1)
 * Attempt 3: wait 4 min (backoff level 2)
 * Attempt 4: wait 8 min (backoff level 3)
 * Attempt 5: wait 16 min (backoff level 4)
 * After: wait 30 min (max backoff)
 * </pre>
 *
 * <h3>P2-19 Requirements</h3>
 *
 * <ul>
 *   <li>Direct Job injection - no JobRegistry dependency
 *   <li>JobRepository query - find FAILED executions
 *   <li>Unique job parameters - timestamp ensures new JobInstance
 *   <li>Retry with exponential backoff - prevent thundering herd
 * </ul>
 *
 * <h3>CLAUDE.md Compliance</h3>
 *
 * <ul>
 *   <li>Section 12: LogicExecutor pattern for exception handling
 *   <li>Section 15: Lambda limit - extracted private methods
 *   <li>Stateless: No mutable instance state (uses JobExplorer for queries)
 * </ul>
 *
 * @see maple.expectation.infrastructure.batch.listener.BatchJobRecoveryListener
 */
@Component
@ConditionalOnBean(JobLauncher::class)
class BatchJobRecoveryScheduler(
    private val jobLauncher: JobLauncher,
    private val jobExplorer: JobExplorer,
    @Qualifier("equipmentRefreshJob") private val equipmentRefreshJob: Job,
    private val recoveryListener: BatchJobRecoveryListener,
    private val lockStrategy: LockStrategy,
    private val executor: LogicExecutor
) {

  /** Maximum number of retry attempts per job instance */
  private val maxRetries = 5

  /** Maximum backoff time (30 minutes) */
  private val maxBackoffMinutes = 30L

  /** Look back window for failed jobs (24 hours) */
  private val failureLookbackHours = 24L

  /** Distributed lock name for recovery */
  private val lockName = "batch-job-recovery-lock"

  /**
   * Recover failed batch jobs (every 60 seconds)
   *
   * <p>Uses distributed lock to ensure only one instance performs recovery.
   * Queries JobExplorer for recent FAILED executions and restarts eligible jobs.
   */
  @Scheduled(fixedRate = 60000) // 60 seconds
  fun recoverFailedJobs() {
    val context = TaskContext.of("BatchRecovery", "recoverFailedJobs")

    executor.executeOrCatch(
        {
          // Acquire distributed lock
          lockStrategy.executeWithLock(
              lockName,
              0,
              10, // 10 second lock timeout
              this::performRecovery
          )
          null
        },
        { e ->
          handleLockFailure(e)
          null
        },
        context
    )
  }

  // ========== Private Methods ==========

  /** Perform the actual recovery - find and restart failed jobs */
  private fun performRecovery() {
    val now = Instant.now()
    val lookbackThreshold = now.minus(failureLookbackHours, ChronoUnit.HOURS)

    // Get failed job metadata from listener
    val failedJobs = recoveryListener.getFailedJobs()

    if (failedJobs.isEmpty()) {
      log.debug("[BatchRecovery] No failed jobs to recover")
      return
    }

    log.info("[BatchRecovery] Found {} failed jobs, checking eligibility for recovery", failedJobs.size)

    var recoveredCount = 0
    var skippedCount = 0

    for ((jobInstanceId, metadata) in failedJobs) {
      val shouldRecover = shouldRecoverJob(metadata, now, lookbackThreshold)

      if (shouldRecover) {
        restartJob(metadata)
        recoveryListener.removeFailedJob(jobInstanceId)
        recoveredCount++
      } else {
        skippedCount++
      }
    }

    logRecoverySummary(recoveredCount, skippedCount)
  }

  /** Determine if a job should be recovered based on retry count and backoff */
  private fun shouldRecoverJob(
      metadata: JobFailureMetadata,
      now: Instant,
      lookbackThreshold: Instant
  ): Boolean {
    // Skip if too old
    if (metadata.timestamp.isBefore(lookbackThreshold)) {
      log.debug("[BatchRecovery] Skipping old failure: {} (timestamp: {})", metadata.jobName, metadata.timestamp)
      return false
    }

    // Check retry count using JobExplorer
    val jobInstance = jobExplorer.getJobInstance(metadata.jobInstanceId) ?: return false
    val executions = jobExplorer.getJobExecutions(jobInstance)

    val failedCount = executions.count { it.status.isUnsuccessful }
    if (failedCount >= maxRetries) {
      log.warn("[BatchRecovery] Skipping {} - exceeded max retries ({})", metadata.jobName, failedCount)
      return false
    }

    // Calculate backoff wait time
    val backoffMinutes = calculateBackoffMinutes(failedCount)
    val timeSinceFailure = ChronoUnit.MINUTES.between(metadata.timestamp, now)

    if (timeSinceFailure < backoffMinutes) {
      log.debug("[BatchRecovery] Skipping {} - backoff wait ({} min remaining)", metadata.jobName, backoffMinutes - timeSinceFailure)
      return false
    }

    return true
  }

  /** Calculate exponential backoff time in minutes */
  private fun calculateBackoffMinutes(failedCount: Int): Long {
    val backoffLevel = (failedCount - 1).coerceAtLeast(0)
    val backoffMinutes = 2.0.pow(backoffLevel.toDouble()).toLong()
    return min(backoffMinutes, maxBackoffMinutes)
  }

  /** Restart a failed job with new parameters */
  private fun restartJob(metadata: JobFailureMetadata) {
    val context = TaskContext.of("BatchRecovery", "restartJob", metadata.jobName)

    executor.executeOrCatch(
        {
          // P2-19: Use injected Job directly (simpler than JobRegistry)
          val job = equipmentRefreshJob

          // P2-19: Add unique timestamp parameter to ensure new JobInstance
          val params = JobParametersBuilder()
              .addLong("timestamp", System.currentTimeMillis())
              .addLong("recovery.attempt", recoveryListener.getFailureCount(metadata.jobName) + 1)
              .addString("trigger", "recovery")
              .toJobParameters()

          log.info("[BatchRecovery] Restarting job: {} with params: {}", metadata.jobName, params)

          jobLauncher.run(job, params)
          log.info("[BatchRecovery] Successfully restarted job: {}", metadata.jobName)

          null
        },
        { e ->
          logRestartFailure(metadata, e)
          null
        },
        context
    )
  }

  /** Handle lock acquisition failure */
  private fun handleLockFailure(e: Throwable) {
    if (e is DistributedLockException) {
      log.debug("[BatchRecovery] Lock acquisition skipped: another instance is handling recovery")
      return
    }
    log.error("[BatchRecovery] Lock acquisition failed: {}", e.message)
  }

  /** Log restart failure */
  private fun logRestartFailure(metadata: JobFailureMetadata, e: Throwable) {
    log.error("[BatchRecovery] Failed to restart job: {} (instanceId: {}) - {}",
        metadata.jobName, metadata.jobInstanceId, e.message)
  }

  /** Log recovery summary */
  private fun logRecoverySummary(recoveredCount: Int, skippedCount: Int) {
    if (recoveredCount > 0 || skippedCount > 0) {
      log.info("[BatchRecovery] Recovery summary - Recovered: {}, Skipped: {}", recoveredCount, skippedCount)
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(BatchJobRecoveryScheduler::class.java)
  }
}
