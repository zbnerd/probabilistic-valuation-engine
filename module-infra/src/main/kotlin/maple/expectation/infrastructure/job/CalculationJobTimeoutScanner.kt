package maple.expectation.infrastructure.job

import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CalculationJobTimeoutScanner(
    private val jobPort: CalculationJobPort,
    private val jobService: CalculationJobService,
    private val executor: LogicExecutor
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${job.scanner.timeout-interval-ms:30000}")
    fun scanStaleJobs() {
        val context = TaskContext.of("TimeoutScanner", "Scan", "stale_jobs")

        executor.executeVoid({
            val staleOcidResolving = jobPort.findStaleJobs(CalculationJobStatus.OCID_RESOLVING, 30)
            for (job in staleOcidResolving) {
                jobService.handleOcidFailure(job.jobId, "OCID_RESOLVE_TIMEOUT", "OCID resolution timeout after 30 seconds")
                log.warn("[jobId={}] Timeout detected: OCID_RESOLVING stale for >30s", job.jobId)
            }

            val staleApiRequested = jobPort.findStaleJobs(CalculationJobStatus.API_REQUESTED, 30)
            for (job in staleApiRequested) {
                jobService.handleApiFailure(job.jobId, "API_TIMEOUT", "API response timeout after 30 seconds")
                log.warn("[jobId={}] Timeout detected: API_REQUESTED stale for >30s", job.jobId)
            }

            val staleRetrying = jobPort.findStaleJobs(CalculationJobStatus.RETRYING, 60)
            for (job in staleRetrying) {
                jobService.handleApiFailure(job.jobId, "RETRY_TIMEOUT", "Retry timeout after 60 seconds")
                log.warn("[jobId={}] Timeout detected: RETRYING stale for >60s", job.jobId)
            }
        }, context)
    }
}
