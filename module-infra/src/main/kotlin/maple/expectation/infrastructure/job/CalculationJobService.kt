package maple.expectation.infrastructure.job

import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CalculationResultData
import maple.expectation.core.port.out.CalculationResultPort
import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.event.NexonApiRequestEventFactory
import maple.expectation.infrastructure.mq.event.NexonApiResponseEventFactory
import maple.expectation.infrastructure.mq.event.OcidResolveEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiRequestTopic
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CalculationJobService(
    private val jobPort: CalculationJobPort,
    private val eventAppender: DomainEventAppender,
    private val ocidResolveTopic: OcidResolveTopic,
    private val nexonApiRequestTopic: NexonApiRequestTopic,
    private val nexonApiResponseTopic: NexonApiResponseTopic,
    private val snapshotRepository: CalculationSnapshotRepository,
    private val resultPort: CalculationResultPort,
    private val outboxPort: OutboxEventPort
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun createJob(ocid: String?, userIgn: String, presetNo: Int): CalculationJob {
        val job = jobPort.createJob(ocid, userIgn, presetNo)
        log.info("[jobId={}] Job created in REQUESTED state", job.jobId)
        return job
    }

    @Transactional
    fun requestOcidResolve(jobId: UUID, userIgn: String, presetNo: Int) {
        val transitioned = jobPort.transitionStatus(
            jobId,
            CalculationJobStatus.REQUESTED,
            CalculationJobStatus.OCID_RESOLVING
        )
        if (!transitioned) {
            log.warn("[jobId={}] Cannot transition to OCID_RESOLVING", jobId)
            return
        }

        eventAppender.append(ocidResolveTopic, OcidResolveEventFactory.create(jobId.toString(), userIgn, presetNo))
        log.info("[jobId={}] Transitioned to OCID_RESOLVING, resolve enqueued", jobId)
    }

    @Transactional
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

        eventAppender.append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(job.jobId.toString(), job.ocid ?: return, job.userIgn, job.presetNo))
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
                eventAppender.append(nexonApiResponseTopic, NexonApiResponseEventFactory.create(jobId.toString(), snapshotId.toString(), objectKey, job.ocid ?: return false, job.userIgn, job.presetNo))
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
                eventAppender.append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(job.jobId.toString(), job.ocid ?: return, job.userIgn, job.presetNo, eventType = "RETRY_FETCH"))
                log.info("[jobId={}] Retrying (attempt {}): {}", jobId, job.retryCount + 1, errorCode)
            }
        }
    }

    @Transactional
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

    @Transactional
    fun completeCalculationWithResult(
        jobId: UUID,
        resultJson: String,
        characterClass: String,
        presetNo: Int,
        characterId: String
    ): Boolean {
        val completed = jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED)
        if (!completed) return false

        val gzipData = gzipCompress(resultJson.toByteArray())
        val hash = sha256Hex(resultJson.toByteArray())

        resultPort.save(CalculationResultData(
            resultId = UUID.randomUUID(),
            jobId = jobId,
            characterClass = characterClass,
            presetNo = presetNo,
            schemaVersion = 1,
            contentType = "application/json",
            contentEncoding = "gzip",
            responseBody = gzipData,
            originalSize = resultJson.toByteArray().size,
            compressedSize = gzipData.size,
            hash = hash,
            status = "SUCCESS"
        ))

        val eventPayload = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(mapOf(
            "jobId" to jobId.toString(),
            "characterId" to characterId,
            "presetNo" to presetNo,
            "contentEncoding" to "gzip",
            "schemaVersion" to 1
        ))
        outboxPort.insertIfAbsent("CALCULATION_COMPLETED", jobId, eventPayload)

        jobPort.unlock(jobId)
        log.info("[jobId={}] Calculation completed with result saved", jobId)
        return true
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
