package maple.expectation.adapter.outgoing

import java.time.Instant
import java.util.UUID
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobClaim
import maple.expectation.core.model.job.CalculationJobRequestKey
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.persistence.entity.CalculationJobEntity
import maple.expectation.infrastructure.persistence.repository.CalculationJobRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class CalculationJobPortAdapter(
    private val jobRepository: CalculationJobRepository,
    private val jdbc: NamedParameterJdbcTemplate,
) : CalculationJobPort {

    private val log = LoggerFactory.getLogger(CalculationJobPortAdapter::class.java)

    override fun createOrFindActiveJob(ocid: String?, userIgn: String, presetNo: Int): CalculationJobClaim {
        val requestKey = CalculationJobRequestKey.of(userIgn, presetNo)
        val jobId = UUID.randomUUID()
        val sql = """
            INSERT INTO calculation_jobs (
                job_id, ocid, user_ign, preset_no, request_key, status,
                retry_count, max_retries, created_at, updated_at
            )
            VALUES (
                :jobId, :ocid, :userIgn, :presetNo, :requestKey, 'REQUESTED',
                0, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT DO NOTHING
            RETURNING job_id
        """.trimIndent()
        val insertedIds = jdbc.queryForList(
            sql,
            mapOf(
                "jobId" to jobId,
                "ocid" to ocid,
                "userIgn" to userIgn,
                "presetNo" to presetNo,
                "requestKey" to requestKey,
            ),
            UUID::class.java,
        )
        if (insertedIds.isNotEmpty()) {
            return CalculationJobClaim(jobRepository.findById(insertedIds.first()).orElseThrow().toDomain(), true)
        }

        val existingByRequestKey = jobRepository.findActiveByRequestKey(requestKey)
        if (existingByRequestKey != null) {
            log.debug("[createJob] Reusing active job by requestKey: userIgn={}, presetNo={}", userIgn, presetNo)
            return CalculationJobClaim(existingByRequestKey.toDomain(), false)
        }

        val existingByLegacyKey = jobRepository.findActiveByUserIgnAndPreset(userIgn, presetNo)
        if (existingByLegacyKey != null) {
            log.debug("[createJob] Reusing active job by legacy key: userIgn={}, presetNo={}", userIgn, presetNo)
            return CalculationJobClaim(existingByLegacyKey.toDomain(), false)
        }

        throw DataIntegrityViolationException("Job insert conflicted but no active job was found: requestKey=$requestKey")
    }

    override fun createJob(ocid: String?, userIgn: String, presetNo: Int): CalculationJob = createOrFindActiveJob(ocid, userIgn, presetNo).job

    override fun findJobById(jobId: UUID): CalculationJob? = jobRepository.findById(jobId).map { it.toDomain() }.orElseGet { null }

    override fun transitionStatus(jobId: UUID, from: CalculationJobStatus, to: CalculationJobStatus): Boolean = jobRepository.transitionStatus(jobId, from.name, to.name) > 0

    override fun markSnapshotReady(jobId: UUID, snapshotId: UUID, from: CalculationJobStatus): Boolean = jobRepository.markSnapshotReady(jobId, snapshotId, from.name) > 0

    @Transactional
    override fun markFailed(jobId: UUID, errorCode: String, errorMessage: String): Boolean = jobRepository.markFailed(jobId, errorCode, errorMessage) > 0

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

    override fun retryCalculation(jobId: UUID, errorCode: String, nextRetryAt: Instant): Boolean = jobRepository.retryCalculation(jobId, errorCode, nextRetryAt) > 0

    override fun lockForProcessing(jobId: UUID, workerId: String, from: CalculationJobStatus): Boolean {
        val lockedUntil = Instant.now().plusSeconds(300)
        return jobRepository.lockForProcessing(jobId, workerId, lockedUntil, from.name) > 0
    }

    override fun unlock(jobId: UUID): Boolean = jobRepository.unlock(jobId) > 0

    override fun findStaleJobs(status: CalculationJobStatus, olderThanSeconds: Long): List<CalculationJob> {
        val cutoff = Instant.now().minusSeconds(olderThanSeconds)
        return jobRepository.findStaleJobs(status.name, cutoff).map { it.toDomain() }
    }

    override fun findJobsByIds(ids: List<UUID>): List<CalculationJob> = jobRepository.findAllById(ids).map { it.toDomain() }

    override fun findActiveJobByUserIgn(userIgn: String, presetNo: Int): CalculationJob? = jobRepository.findActiveByUserIgnAndPreset(userIgn, presetNo)?.toDomain()

    override fun resolveOcidAndTransition(jobId: UUID, ocid: String): Boolean = jobRepository.resolveOcidAndTransition(jobId, ocid) > 0

    override fun completeFromSnapshotReady(jobId: UUID): Boolean = jobRepository.completeFromSnapshotReady(jobId) > 0

    override fun completeFromCalculating(jobId: UUID): Boolean = jobRepository.completeFromCalculating(jobId) > 0

    private fun CalculationJobEntity.toDomain() = CalculationJob(
        jobId = jobId,
        ocid = ocid,
        userIgn = userIgn,
        presetNo = presetNo,
        requestKey = requestKey,
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
        completedAt = completedAt,
    )
}
