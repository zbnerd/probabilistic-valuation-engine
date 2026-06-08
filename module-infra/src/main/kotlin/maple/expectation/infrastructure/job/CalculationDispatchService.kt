package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import maple.expectation.infrastructure.pgmq.CalculationCompletedPayload
import maple.expectation.infrastructure.pgmq.CalculationRequestedPayload
import maple.expectation.infrastructure.pgmq.ExternalApiJobPayload
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.queue.QueueNames
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CalculationDispatchService(
    private val jobPort: CalculationJobPort,
    private val pgmqClient: PgmqClient,
    private val snapshotRepository: CalculationSnapshotRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(value = "transactionManager", readOnly = false)
    fun retryOcidResolvingJob(jobId: UUID, userIgn: String, presetNo: Int): Boolean {
        val incremented = jobPort.incrementRetryForOcid(jobId, "OCID_RESOLVE_TIMEOUT")
        if (incremented) {
            pgmqClient.send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(jobId.toString(), userIgn, presetNo))
        }
        return incremented
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun retryApiRequestedJob(jobId: UUID, userIgn: String, presetNo: Int): Boolean {
        val incremented = jobPort.incrementRetry(jobId, "EXTERNAL_API_TIMEOUT")
        if (incremented) {
            pgmqClient.send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(jobId.toString(), userIgn, presetNo))
        }
        return incremented
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun dispatchToExternalApi(jobId: UUID, userIgn: String, presetNo: Int) {
        val transitioned = jobPort.transitionStatus(
            jobId,
            CalculationJobStatus.REQUESTED,
            CalculationJobStatus.OCID_RESOLVING,
        )
        if (!transitioned) {
            log.warn("[jobId={}] Cannot transition to OCID_RESOLVING", jobId)
            return
        }

        pgmqClient.send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(jobId.toString(), userIgn, presetNo))
        log.info("[jobId={}] Dispatched to consolidated external API pipeline", jobId)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun dispatchCalculationCompleted(payload: CalculationCompletedPayload) {
        pgmqClient.send(QueueNames.CALCULATION_COMPLETED, payload)
        log.info("[jobId={}] Calculation completed payload dispatched", payload.jobId)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun saveInputSnapshotAndDispatchCalculation(
        snapshotEntity: CalculationSnapshotEntity,
        jobId: UUID,
        snapshotId: UUID,
        payload: CalculationRequestedPayload,
    ): Boolean {
        snapshotRepository.save(snapshotEntity)
        val ready = jobPort.markSnapshotReady(jobId, snapshotId, CalculationJobStatus.API_REQUESTED)
        if (!ready) {
            log.warn("[jobId={}] Cannot mark SNAPSHOT_READY before calculation dispatch", jobId)
            return false
        }
        pgmqClient.send(QueueNames.CALCULATION_REQUESTED, payload)
        log.info("[jobId={}] Calculation requested", jobId)
        return true
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun retryExternalApiJob(jobId: UUID, errorCode: String = "EXTERNAL_API_ERROR"): Boolean {
        val job = jobPort.findJobById(jobId) ?: return false
        if (job.retryCount >= job.maxRetries) {
            jobPort.markFailed(jobId, errorCode, "Max retries exceeded")
            log.warn("[jobId={}] External API failed after {} retries", jobId, job.retryCount)
            return true
        }
        val incremented = when (job.status) {
            CalculationJobStatus.OCID_RESOLVING -> jobPort.incrementRetryForOcid(jobId, errorCode)
            CalculationJobStatus.API_REQUESTED,
            CalculationJobStatus.RETRYING,
            -> jobPort.incrementRetry(jobId, errorCode)
            CalculationJobStatus.REQUESTED -> jobPort.transitionStatus(
                jobId,
                CalculationJobStatus.REQUESTED,
                CalculationJobStatus.OCID_RESOLVING,
            )
            else -> false
        }
        if (!incremented) {
            log.warn("[jobId={}] External API retry not scheduled from state {}", jobId, job.status)
            return false
        }
        pgmqClient.send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
        log.info("[jobId={}] External API retry scheduled (attempt {})", jobId, job.retryCount + 1)
        return true
    }
}
