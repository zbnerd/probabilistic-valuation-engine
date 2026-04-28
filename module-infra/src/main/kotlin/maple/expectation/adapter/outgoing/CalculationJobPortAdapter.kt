package maple.expectation.adapter.outgoing

import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.persistence.entity.CalculationJobEntity
import maple.expectation.infrastructure.persistence.repository.CalculationJobRepository
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class CalculationJobPortAdapter(
    private val jobRepository: CalculationJobRepository,
    private val jdbc: NamedParameterJdbcTemplate
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

    override fun incrementRetryForOcid(jobId: UUID, errorCode: String): Boolean {
        val backoffSeconds = 30L
        val nextRetry = Instant.now().plusSeconds(backoffSeconds)
        return jobRepository.incrementRetryForOcid(jobId, errorCode, nextRetry) > 0
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

    override fun resolveOcidAndTransition(jobId: UUID, ocid: String): Boolean {
        return jobRepository.resolveOcidAndTransition(jobId, ocid) > 0
    }

    override fun findCompletedJobsMissingOutboxEvents(limit: Int): List<UUID> {
        val sql = """
            SELECT j.job_id FROM calculation_jobs j
            WHERE j.status = 'COMPLETED'
              AND j.completed_at < now() - INTERVAL '1 minute'
              AND NOT EXISTS (
                SELECT 1 FROM outbox_events o
                WHERE o.job_id = j.job_id AND o.event_type = 'CALCULATION_COMPLETED'
              )
            LIMIT :limit
        """.trimIndent()
        return jdbc.queryForList(sql, mapOf("limit" to limit), UUID::class.java)
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
