package maple.expectation.infrastructure.job

import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CalculationJobTimeoutScanner(
    private val jobPort: CalculationJobPort,
    private val jobService: CalculationJobService,
    private val executor: LogicExecutor,
    @Value("\${job.scanner.max-batch-size:20}") private val maxBatchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${job.scanner.timeout-interval-ms:30000}")
    fun scanStaleJobs() {
        val context = TaskContext.of("TimeoutScanner", "Scan", "stale_jobs")

        executor.executeVoid({
            scanOcidResolving()
            scanApiRequested()
            scanRetrying()
        }, context)
    }

    private fun scanOcidResolving() {
        val candidates = jobPort.findStaleJobs(CalculationJobStatus.OCID_RESOLVING, 120)
            .take(maxBatchSize)
        if (candidates.isEmpty()) return

        val currentJobs = jobPort.findJobsByIds(candidates.map { it.jobId })
        for (job in currentJobs) {
            if (job.status != CalculationJobStatus.OCID_RESOLVING) continue
            if (job.retryCount >= job.maxRetries) {
                jobPort.markFailed(job.jobId, "OCID_RESOLVE_TIMEOUT", "Max retries exceeded")
                log.warn("[jobId={}] Marked failed: OCID_RESOLVING max retries ({}) exhausted", job.jobId, job.maxRetries)
                continue
            }
            val retried = jobService.retryOcidResolvingJob(job.jobId, job.userIgn, job.presetNo)
            if (retried) {
                log.warn("[jobId={}] OCID_RESOLVING stale >120s, retried (attempt {})", job.jobId, job.retryCount + 1)
            }
        }
    }

    private fun scanApiRequested() {
        val candidates = jobPort.findStaleJobs(CalculationJobStatus.API_REQUESTED, 300)
            .take(maxBatchSize)
        if (candidates.isEmpty()) return

        val currentJobs = jobPort.findJobsByIds(candidates.map { it.jobId })
        for (job in currentJobs) {
            if (job.status != CalculationJobStatus.API_REQUESTED) continue
            if (job.retryCount >= job.maxRetries) {
                jobPort.markFailed(job.jobId, "API_TIMEOUT", "Max retries exceeded")
                log.warn("[jobId={}] Marked failed: API_REQUESTED max retries ({}) exhausted", job.jobId, job.maxRetries)
                continue
            }
            val retried = jobService.retryApiRequestedJob(job.jobId, job.userIgn, job.presetNo)
            if (retried) {
                log.warn("[jobId={}] API_REQUESTED stale >300s, retried (attempt {})", job.jobId, job.retryCount + 1)
            }
        }
    }

    private fun scanRetrying() {
        val candidates = jobPort.findStaleJobs(CalculationJobStatus.RETRYING, 180)
            .take(maxBatchSize)
        if (candidates.isEmpty()) return

        val currentJobs = jobPort.findJobsByIds(candidates.map { it.jobId })
        for (job in currentJobs) {
            if (job.status != CalculationJobStatus.RETRYING) continue
            if (job.retryCount >= job.maxRetries) {
                jobPort.markFailed(job.jobId, "RETRY_TIMEOUT", "Max retries exceeded")
                log.warn("[jobId={}] Marked failed: RETRYING max retries ({}) exhausted", job.jobId, job.maxRetries)
                continue
            }
            val retried = jobService.retryApiRequestedJob(job.jobId, job.userIgn, job.presetNo)
            if (retried) {
                log.warn("[jobId={}] RETRYING stale >180s, retried (attempt {})", job.jobId, job.retryCount + 1)
            }
        }
    }
}
