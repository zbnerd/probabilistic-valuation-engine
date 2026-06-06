package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobClaim
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.event.NexonApiRequestEventFactory
import maple.expectation.infrastructure.mq.event.NexonApiResponseEventFactory
import maple.expectation.infrastructure.mq.event.OcidResolveEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiRequestTopic
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
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
    private val eventAppender: DomainEventAppender,
    private val ocidResolveTopic: OcidResolveTopic,
    private val nexonApiRequestTopic: NexonApiRequestTopic,
    private val nexonApiResponseTopic: NexonApiResponseTopic,
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
    fun requestOcidResolve(jobId: UUID, userIgn: String, presetNo: Int) {
        val transitioned = jobPort.transitionStatus(
            jobId,
            CalculationJobStatus.REQUESTED,
            CalculationJobStatus.OCID_RESOLVING,
        )
        if (!transitioned) {
            log.warn("[jobId={}] Cannot transition to OCID_RESOLVING", jobId)
            return
        }

        eventAppender.append(ocidResolveTopic, OcidResolveEventFactory.create(jobId.toString(), userIgn, presetNo))
        log.info("[jobId={}] Transitioned to OCID_RESOLVING, resolve enqueued", jobId)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun resolveOcidAndEnqueueApiData(jobId: UUID, ocid: String): Boolean {
        val transitioned = jobPort.resolveOcidAndTransition(jobId, ocid)
        if (!transitioned) {
            log.warn("[jobId={}] Cannot resolve OCID + transition to API_REQUESTED", jobId)
            return false
        }

        val job = jobPort.findJobById(jobId) ?: return false

        eventAppender.append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(job.jobId.toString(), ocid, job.userIgn, job.presetNo))
        log.info("[jobId={}] OCID resolved, API request enqueued", jobId)
        return true
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun saveSnapshotAndMarkReady(
        snapshotEntity: CalculationSnapshotEntity,
        jobId: UUID,
        objectKey: String,
    ): Boolean {
        snapshotRepository.save(snapshotEntity)
        return markSnapshotReadyInternal(jobId, snapshotEntity.snapshotId, objectKey)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun markSnapshotReady(jobId: UUID, snapshotId: UUID, objectKey: String): Boolean = markSnapshotReadyInternal(jobId, snapshotId, objectKey)

    private fun markSnapshotReadyInternal(jobId: UUID, snapshotId: UUID, objectKey: String): Boolean {
        val ready = jobPort.markSnapshotReady(jobId, snapshotId, CalculationJobStatus.API_REQUESTED)
        if (ready) {
            val job = jobPort.findJobById(jobId)
            if (job != null) {
                eventAppender.append(nexonApiResponseTopic, NexonApiResponseEventFactory.create(jobId.toString(), snapshotId.toString(), objectKey, job.ocid ?: return false, job.userIgn, job.presetNo))
                log.info("[jobId={}] Snapshot ready, response enqueued", jobId)
            }
        }
        return ready
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun handleApiFailure(jobId: UUID, errorCode: String, errorMessage: String) {
        val job = jobPort.findJobById(jobId) ?: return

        if (job.retryCount >= job.maxRetries) {
            jobPort.markFailed(jobId, errorCode, errorMessage)
            log.warn("[jobId={}] Failed after {} retries: {}", jobId, job.retryCount, errorMessage)
        } else {
            val retried = jobPort.incrementRetry(jobId, errorCode)
            if (retried) {
                eventAppender.append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(job.jobId.toString(), job.ocid ?: return, job.userIgn, job.presetNo, eventType = "RETRY_FETCH"))
                log.info("[jobId={}] Retrying (attempt {}): {}", jobId, job.retryCount + 1, errorCode)
            }
        }
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun handleOcidFailure(jobId: UUID, errorCode: String, errorMessage: String) {
        val job = jobPort.findJobById(jobId) ?: return

        if (job.retryCount >= job.maxRetries) {
            jobPort.markFailed(jobId, errorCode, errorMessage)
            log.warn("[jobId={}] OCID resolve failed after {} retries: {}", jobId, job.retryCount, errorMessage)
        } else {
            val retried = jobPort.incrementRetryForOcid(jobId, errorCode)
            if (retried) {
                eventAppender.append(ocidResolveTopic, OcidResolveEventFactory.create(job.jobId.toString(), job.userIgn, job.presetNo))
                log.info("[jobId={}] OCID resolve retry (attempt {}): {}", jobId, job.retryCount + 1, errorCode)
            }
        }
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun resolveOcidInPlace(jobId: UUID, ocid: String): Boolean = jobPort.resolveOcidAndTransition(jobId, ocid)

    @Transactional(value = "transactionManager", readOnly = false)
    fun saveInputSnapshotAndMarkReady(
        snapshotEntity: CalculationSnapshotEntity,
        jobId: UUID,
        snapshotId: UUID,
    ): Boolean {
        snapshotRepository.save(snapshotEntity)
        return jobPort.markSnapshotReady(jobId, snapshotId, CalculationJobStatus.API_REQUESTED)
    }

    fun retryOcidResolvingJob(jobId: UUID, userIgn: String, presetNo: Int): Boolean =
        dispatchService.retryOcidResolvingJob(jobId, userIgn, presetNo)

    fun retryApiRequestedJob(jobId: UUID, userIgn: String, presetNo: Int): Boolean =
        dispatchService.retryApiRequestedJob(jobId, userIgn, presetNo)

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

    fun retryExternalApiJob(jobId: UUID, errorCode: String = "EXTERNAL_API_ERROR"): Boolean =
        dispatchService.retryExternalApiJob(jobId, errorCode)
}
