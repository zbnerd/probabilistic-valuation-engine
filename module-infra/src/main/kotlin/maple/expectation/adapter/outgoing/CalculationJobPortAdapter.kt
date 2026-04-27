package maple.expectation.adapter.outgoing

import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.persistence.entity.CalculationJobEntity
import maple.expectation.infrastructure.persistence.repository.CalculationJobRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class CalculationJobPortAdapter(
    private val jobRepository: CalculationJobRepository
) : CalculationJobPort {

    override fun createJob(ocid: String?, userIgn: String, presetNo: Int): CalculationJob {
        val existing = jobRepository.findActiveByUserIgnAndPreset(userIgn, presetNo)
        if (existing != null) {
            return existing.toDomain()
        }

        val entity = CalculationJobEntity(
            ocid = ocid,
            userIgn = userIgn,
            presetNo = presetNo
        )
        return jobRepository.save(entity).toDomain()
    }

    override fun findJobById(jobId: UUID): CalculationJob? {
        return jobRepository.findById(jobId).orElse(null)?.toDomain()
    }

    override fun transitionStatus(jobId: UUID, from: CalculationJobStatus, to: CalculationJobStatus): Boolean {
        return jobRepository.transitionStatus(jobId, from.name, to.name) > 0
    }

    override fun markSnapshotReady(jobId: UUID, snapshotId: UUID, from: CalculationJobStatus): Boolean {
        return jobRepository.markSnapshotReady(jobId, snapshotId, from.name) > 0
    }

    override fun markFailed(jobId: UUID, errorCode: String, errorMessage: String): Boolean {
        return jobRepository.markFailed(jobId, errorCode, errorMessage) > 0
    }

    override fun incrementRetry(jobId: UUID, errorCode: String): Boolean {
        val backoffSeconds = 30L
        val nextRetry = Instant.now().plusSeconds(backoffSeconds)
        return jobRepository.incrementRetry(jobId, errorCode, nextRetry) > 0
    }

    override fun lockForProcessing(jobId: UUID, workerId: String, from: CalculationJobStatus): Boolean {
        val lockedUntil = Instant.now().plusSeconds(300)
        return jobRepository.lockForProcessing(jobId, workerId, lockedUntil, from.name) > 0
    }

    override fun unlock(jobId: UUID): Boolean {
        return jobRepository.unlock(jobId) > 0
    }

    override fun findStaleJobs(status: CalculationJobStatus, olderThanSeconds: Long): List<CalculationJob> {
        val cutoff = Instant.now().minusSeconds(olderThanSeconds)
        return jobRepository.findStaleJobs(status.name, cutoff).map { it.toDomain() }
    }

    override fun findActiveJobByUserIgn(userIgn: String, presetNo: Int): CalculationJob? {
        return jobRepository.findActiveByUserIgnAndPreset(userIgn, presetNo)?.toDomain()
    }

    override fun resolveOcid(jobId: UUID, ocid: String, from: CalculationJobStatus): Boolean {
        return jobRepository.resolveOcid(jobId, ocid, from.name, CalculationJobStatus.OCID_RESOLVED.name) > 0
    }

    private fun CalculationJobEntity.toDomain() = CalculationJob(
        jobId = jobId,
        ocid = ocid,
        userIgn = userIgn,
        presetNo = presetNo,
        status = CalculationJobStatus.valueOf(status),
        snapshotId = snapshotId,
        retryCount = retryCount,
        maxRetries = maxRetries,
        nextRetryAt = nextRetryAt,
        lockedBy = lockedBy,
        lockedUntil = lockedUntil,
        lastErrorCode = lastErrorCode,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt
    )
}
