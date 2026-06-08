package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.event.NexonApiRequestEventFactory
import maple.expectation.infrastructure.mq.event.NexonApiResponseEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiRequestTopic
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ApiDataFetchOrchestrator(
    private val jobPort: CalculationJobPort,
    private val eventAppender: DomainEventAppender,
    private val snapshotRepository: CalculationSnapshotRepository,
    private val nexonApiRequestTopic: NexonApiRequestTopic,
    private val nexonApiResponseTopic: NexonApiResponseTopic,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
}
