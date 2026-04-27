package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.CalculationJobEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface CalculationJobRepository : JpaRepository<CalculationJobEntity, UUID> {

    @Query("""
        SELECT j FROM CalculationJobEntity j
        WHERE j.userIgn = :userIgn AND j.presetNo = :presetNo
          AND j.status IN ('REQUESTED', 'OCID_RESOLVING', 'OCID_RESOLVED', 'API_REQUESTED', 'SNAPSHOT_READY', 'CALCULATING', 'RETRYING')
    """)
    fun findActiveByUserIgnAndPreset(@Param("userIgn") userIgn: String, @Param("presetNo") presetNo: Int): CalculationJobEntity?

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.status = :to, j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId AND j.status = :from
    """)
    fun transitionStatus(
        @Param("jobId") jobId: UUID,
        @Param("from") from: String,
        @Param("to") to: String
    ): Int

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.status = 'SNAPSHOT_READY', j.snapshotId = :snapshotId,
            j.lockedBy = NULL, j.lockedUntil = NULL, j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId AND j.status = :from
    """)
    fun markSnapshotReady(
        @Param("jobId") jobId: UUID,
        @Param("snapshotId") snapshotId: UUID,
        @Param("from") from: String
    ): Int

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.status = 'FAILED', j.lastErrorCode = :errorCode,
            j.errorMessage = :errorMessage, j.completedAt = CURRENT_TIMESTAMP,
            j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId
          AND j.status NOT IN ('COMPLETED', 'FAILED')
    """)
    fun markFailed(
        @Param("jobId") jobId: UUID,
        @Param("errorCode") errorCode: String,
        @Param("errorMessage") errorMessage: String
    ): Int

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.retryCount = j.retryCount + 1,
            j.status = 'API_REQUESTED',
            j.nextRetryAt = :nextRetryAt,
            j.lastErrorCode = :errorCode,
            j.lockedBy = NULL, j.lockedUntil = NULL,
            j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId
          AND j.status IN ('API_REQUESTED', 'RETRYING')
          AND j.retryCount < j.maxRetries
    """)
    fun incrementRetry(
        @Param("jobId") jobId: UUID,
        @Param("errorCode") errorCode: String,
        @Param("nextRetryAt") nextRetryAt: Instant
    ): Int

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.retryCount = j.retryCount + 1,
            j.status = 'OCID_RESOLVING',
            j.nextRetryAt = :nextRetryAt,
            j.lastErrorCode = :errorCode,
            j.lockedBy = NULL, j.lockedUntil = NULL,
            j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId
          AND j.status = 'OCID_RESOLVING'
          AND j.retryCount < j.maxRetries
    """)
    fun incrementRetryForOcid(
        @Param("jobId") jobId: UUID,
        @Param("errorCode") errorCode: String,
        @Param("nextRetryAt") nextRetryAt: Instant
    ): Int

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.ocid = :ocid, j.status = 'API_REQUESTED',
            j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId AND j.status = 'OCID_RESOLVING'
    """)
    fun resolveOcidAndTransition(
        @Param("jobId") jobId: UUID,
        @Param("ocid") ocid: String
    ): Int

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.lockedBy = :workerId,
            j.lockedUntil = :lockedUntil,
            j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId AND j.status = :from
          AND (j.lockedUntil IS NULL OR j.lockedUntil < CURRENT_TIMESTAMP)
    """)
    fun lockForProcessing(
        @Param("jobId") jobId: UUID,
        @Param("workerId") workerId: String,
        @Param("lockedUntil") lockedUntil: Instant,
        @Param("from") from: String
    ): Int

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.lockedBy = NULL, j.lockedUntil = NULL, j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId
    """)
    fun unlock(@Param("jobId") jobId: UUID): Int

    @Query("""
        SELECT j FROM CalculationJobEntity j
        WHERE j.status = :status AND j.updatedAt < :cutoff
    """)
    fun findStaleJobs(
        @Param("status") status: String,
        @Param("cutoff") cutoff: Instant
    ): List<CalculationJobEntity>
}
