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
            val staleOcidResolving = jobPort.findStaleJobs(CalculationJobStatus.OCID_RESOLVING, 120)
                .take(maxBatchSize)
            for (job in staleOcidResolving) {
                val current = jobPort.findJobById(job.jobId)
                if (current != null && current.status == CalculationJobStatus.OCID_RESOLVING) {
                    jobService.retryExternalApiJob(job.jobId)
                    log.warn("[jobId={}] Timeout: OCID_RESOLVING stale >120s, retried via consolidated queue", job.jobId)
                }
            }

            val staleApiRequested = jobPort.findStaleJobs(CalculationJobStatus.API_REQUESTED, 300)
                .take(maxBatchSize)
            for (job in staleApiRequested) {
                val current = jobPort.findJobById(job.jobId)
                if (current != null && current.status == CalculationJobStatus.API_REQUESTED) {
                    jobService.retryExternalApiJob(job.jobId)
                    log.warn("[jobId={}] Timeout: API_REQUESTED stale >300s, retried via consolidated queue", job.jobId)
                }
            }

            val staleRetrying = jobPort.findStaleJobs(CalculationJobStatus.RETRYING, 180)
                .take(maxBatchSize)
            for (job in staleRetrying) {
                val current = jobPort.findJobById(job.jobId)
                if (current != null && current.status == CalculationJobStatus.RETRYING) {
                    jobService.retryExternalApiJob(job.jobId)
                    log.warn("[jobId={}] Timeout: RETRYING stale >180s, retried via consolidated queue", job.jobId)
                }
            }
        }, context)
    }
}
