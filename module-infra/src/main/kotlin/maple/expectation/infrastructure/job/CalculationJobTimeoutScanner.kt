package maple.expectation.infrastructure.job

import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CalculationJobTimeoutScanner(
    private val jobPort: CalculationJobPort,
    private val jobService: CalculationJobService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${job.scanner.timeout-interval-ms:30000}")
    fun scanStaleJobs() {
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
    }
}
