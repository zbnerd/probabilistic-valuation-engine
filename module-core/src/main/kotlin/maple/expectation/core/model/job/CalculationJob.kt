package maple.expectation.core.model.job

import java.time.Instant
import java.util.UUID

data class CalculationJob(
    val jobId: UUID,
    val ocid: String?,
    val userIgn: String,
    val presetNo: Int = 1,
    val status: CalculationJobStatus = CalculationJobStatus.REQUESTED,
    val snapshotId: UUID? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val nextRetryAt: Instant? = null,
    val lockedBy: String? = null,
    val lockedUntil: Instant? = null,
    val lastErrorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null
)
