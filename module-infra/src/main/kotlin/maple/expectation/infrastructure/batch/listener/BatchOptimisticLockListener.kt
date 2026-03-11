package maple.expectation.infrastructure.batch.listener

import maple.expectation.infrastructure.batch.listener.BatchJobRecoveryListener
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.mongodb.BatchCharacterViewService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component

/**
 * Batch Optimistic Lock Listener - Stage and Swap Pattern (Unit 5: Batch-Realtime Race Condition Fix)
 *
 * <h3>Purpose</h3>
 *
 * Orchestrates the staging collection lifecycle for batch jobs:
 *
 * <ul>
 *   <li><b>Before job:</b> Clear staging collection
 *   <li><b>After job (success):</b> Atomic swap staging -> production
 *   <li><b>After job (failure):</b> Log error, no swap (staging discarded)
 * </ul>
 *
 * <h3>Stage and Swap Pattern</h3>
 *
 * <p>This pattern ensures that:
 *
 * <ul>
 *   <li>Realtime updates continue to work during batch processing
 *   <li>Batch data is built atomically and swapped in one operation
 *   <li>If batch fails, no partial data pollutes production collection
 * </ul>
 *
 * <h3>Timeline</h3>
 *
 * <pre>
 * T0: beforeJob() - Clear staging collection
 * T1: Batch writes to staging (not production)
 * T2: Realtime updates continue to production
 * T3: afterJob() - Atomic swap staging -> production (if success)
 * </pre>
 *
 * <h3>CLAUDE.md Compliance</h3>
 *
 * <ul>
 *   <li>Section 12: LogicExecutor pattern for exception handling
 *   <li>Section 15: Lambda limit - extracted private methods
 *   <li>Stateless: No mutable instance state
 * </ul>
 */
@Component
@ConditionalOnBean(name = ["batchCharacterViewService"])
class BatchOptimisticLockListener(
    private val batchViewService: BatchCharacterViewService,
    private val executor: LogicExecutor,
) : JobExecutionListener {

    /**
     * Before job - Clear staging collection for fresh batch data.
     *
     * <p>Ensures clean state for each batch run.
     */
    override fun beforeJob(jobExecution: JobExecution) {
        val context = TaskContext.of("BatchOptimisticLock", "beforeJob")

        executor.executeVoidJava({
            val jobName = jobExecution.jobInstance.jobName
            log.info("[BatchOptimisticLock] Clearing staging collection for job: {}", jobName)

            batchViewService.clearStaging()

            log.info("[BatchOptimisticLock] Staging collection cleared, batch can proceed")
        }, context)
    }

    /**
     * After job - Perform atomic swap if job succeeded.
     *
     * <p>Only swaps on successful completion to avoid partial data pollution.
     */
    override fun afterJob(jobExecution: JobExecution) {
        val context = TaskContext.of("BatchOptimisticLock", "afterJob")

        executor.executeVoidJava({
            val jobName = jobExecution.jobInstance.jobName
            val status = jobExecution.status

            if (status.name == "COMPLETED") {
                log.info("[BatchOptimisticLock] Job completed successfully, performing atomic swap: {}", jobName)

                val swapped = batchViewService.swapStagingToProduction()

                if (swapped) {
                    log.info("[BatchOptimisticLock] Atomic swap completed successfully: {}", jobName)
                } else {
                    log.error("[BatchOptimisticLock] Atomic swap failed: {}", jobName)
                }
            } else {
                log.warn(
                    "[BatchOptimisticLock] Job did not complete successfully (status: {}), skipping swap: {}",
                    status,
                    jobName,
                )
            }
        }, context)
    }

    companion object {
        private val log = LoggerFactory.getLogger(BatchOptimisticLockListener::class.java)
    }
}
