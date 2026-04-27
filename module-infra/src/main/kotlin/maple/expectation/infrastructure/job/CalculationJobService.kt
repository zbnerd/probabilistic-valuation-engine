package maple.expectation.infrastructure.job

import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.QueueNames
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.queue.pgmq.NexonApiRequestMessage
import maple.expectation.infrastructure.queue.pgmq.NexonApiResponseMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class CalculationJobService(
    private val jobPort: CalculationJobPort,
    private val pgmqClient: PgmqClient,
    private val snapshotRepository: CalculationSnapshotRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun createJob(ocid: String, userIgn: String, presetNo: Int): CalculationJob {
        val job = jobPort.createJob(ocid, userIgn, presetNo)
        log.info("[jobId={}] Job created in REQUESTED state", job.jobId)
        return job
    }

    @Transactional
    fun requestApiData(jobId: UUID) {
        val transitioned = jobPort.transitionStatus(
            jobId,
            CalculationJobStatus.REQUESTED,
            CalculationJobStatus.API_REQUESTED
        )
        if (!transitioned) {
            log.warn("[jobId={}] Cannot transition to API_REQUESTED", jobId)
            return
        }

        val job = jobPort.findJobById(jobId) ?: return

        val request = NexonApiRequestMessage(
            jobId = job.jobId,
            ocid = job.ocid,
            userIgn = job.userIgn,
            presetNo = job.presetNo,
            eventType = "FETCH_EQUIPMENT",
            requestedAt = Instant.now().toString()
        )
        pgmqClient.send(QueueNames.NEXON_API_REQUEST, request)
        log.info("[jobId={}] Transitioned to API_REQUESTED, request enqueued", jobId)
    }

    @Transactional
    fun saveSnapshotAndMarkReady(
        snapshotEntity: CalculationSnapshotEntity,
        jobId: UUID,
        objectKey: String
    ): Boolean {
        snapshotRepository.save(snapshotEntity)
        return markSnapshotReadyInternal(jobId, snapshotEntity.snapshotId, objectKey)
    }

    @Transactional
    fun markSnapshotReady(jobId: UUID, snapshotId: UUID, objectKey: String): Boolean {
        return markSnapshotReadyInternal(jobId, snapshotId, objectKey)
    }

    private fun markSnapshotReadyInternal(jobId: UUID, snapshotId: UUID, objectKey: String): Boolean {
        val ready = jobPort.markSnapshotReady(jobId, snapshotId, CalculationJobStatus.API_REQUESTED)
        if (ready) {
            val job = jobPort.findJobById(jobId)
            if (job != null) {
                val response = NexonApiResponseMessage(
                    eventType = "SNAPSHOT_READY",
                    jobId = jobId,
                    snapshotId = snapshotId,
                    objectKey = objectKey,
                    characterId = job.ocid,
                    userIgn = job.userIgn,
                    presetNo = job.presetNo
                )
                pgmqClient.send(QueueNames.NEXON_API_RESPONSE, response)
                log.info("[jobId={}] Snapshot ready, response enqueued", jobId)
            }
        }
        return ready
    }

    @Transactional
    fun startCalculation(jobId: UUID, workerId: String): Boolean {
        val locked = jobPort.lockForProcessing(jobId, workerId, CalculationJobStatus.SNAPSHOT_READY)
        if (locked) {
            jobPort.transitionStatus(jobId, CalculationJobStatus.SNAPSHOT_READY, CalculationJobStatus.CALCULATING)
            log.info("[jobId={}] Calculation started by {}", jobId, workerId)
        }
        return locked
    }

    @Transactional
    fun completeCalculation(jobId: UUID): Boolean {
        val completed = jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED)
        if (completed) {
            jobPort.unlock(jobId)
            log.info("[jobId={}] Calculation completed", jobId)
        }
        return completed
    }

    @Transactional
    fun handleApiFailure(jobId: UUID, errorCode: String, errorMessage: String) {
        val job = jobPort.findJobById(jobId) ?: return

        if (job.retryCount >= job.maxRetries) {
            jobPort.markFailed(jobId, errorCode, errorMessage)
            log.warn("[jobId={}] Failed after {} retries: {}", jobId, job.retryCount, errorMessage)
        } else {
            val retried = jobPort.incrementRetry(jobId, errorCode)
            if (retried) {
                val request = NexonApiRequestMessage(
                    jobId = job.jobId,
                    ocid = job.ocid,
                    userIgn = job.userIgn,
                    presetNo = job.presetNo,
                    eventType = "RETRY_FETCH",
                    requestedAt = Instant.now().toString()
                )
                pgmqClient.send(QueueNames.NEXON_API_REQUEST, request)
                log.info("[jobId={}] Retrying (attempt {}): {}", jobId, job.retryCount + 1, errorCode)
            }
        }
    }
}
