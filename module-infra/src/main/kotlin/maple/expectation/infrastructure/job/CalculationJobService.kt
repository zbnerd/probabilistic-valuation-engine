package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobClaim
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import maple.expectation.infrastructure.pgmq.CalculationCompletedPayload
import maple.expectation.infrastructure.pgmq.CalculationRequestedPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CalculationJobService(
    private val jobPort: CalculationJobPort,
    private val snapshotRepository: CalculationSnapshotRepository,
    private val dispatchService: CalculationDispatchService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(value = "transactionManager", readOnly = false)
    fun createJob(ocid: String?, userIgn: String, presetNo: Int): CalculationJob {
        val job = jobPort.createJob(ocid, userIgn, presetNo)
        log.info("[jobId={}] Job created in REQUESTED state", job.jobId)
        return job
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun createOrFindActiveJob(ocid: String?, userIgn: String, presetNo: Int): CalculationJobClaim {
        val claim = jobPort.createOrFindActiveJob(ocid, userIgn, presetNo)
        if (claim.created) {
            log.info("[jobId={}] Job claimed in REQUESTED state", claim.job.jobId)
        } else {
            log.debug("[jobId={}] Existing active job reused", claim.job.jobId)
        }
        return claim
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun saveInputSnapshotAndMarkReady(
        snapshotEntity: CalculationSnapshotEntity,
        jobId: UUID,
        snapshotId: UUID,
    ): Boolean {
        snapshotRepository.save(snapshotEntity)
        return jobPort.markSnapshotReady(jobId, snapshotId, CalculationJobStatus.API_REQUESTED)
    }

    fun retryOcidResolvingJob(jobId: UUID, userIgn: String, presetNo: Int): Boolean = dispatchService.retryOcidResolvingJob(jobId, userIgn, presetNo)

    fun retryApiRequestedJob(jobId: UUID, userIgn: String, presetNo: Int): Boolean = dispatchService.retryApiRequestedJob(jobId, userIgn, presetNo)

    fun dispatchToExternalApi(jobId: UUID, userIgn: String, presetNo: Int) {
        dispatchService.dispatchToExternalApi(jobId, userIgn, presetNo)
    }

    fun dispatchCalculationCompleted(payload: CalculationCompletedPayload) {
        dispatchService.dispatchCalculationCompleted(payload)
    }

    fun saveInputSnapshotAndDispatchCalculation(
        snapshotEntity: CalculationSnapshotEntity,
        jobId: UUID,
        snapshotId: UUID,
        payload: CalculationRequestedPayload,
    ): Boolean = dispatchService.saveInputSnapshotAndDispatchCalculation(snapshotEntity, jobId, snapshotId, payload)

    fun retryExternalApiJob(jobId: UUID, errorCode: String = "EXTERNAL_API_ERROR"): Boolean = dispatchService.retryExternalApiJob(jobId, errorCode)
}
