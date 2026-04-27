package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "calculation_jobs")
open class CalculationJobEntity(

    @Id
    @Column(updatable = false, nullable = false)
    val jobId: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 64)
    val ocid: String,

    @Column(nullable = false, length = 64)
    val userIgn: String,

    @Column(nullable = false)
    val presetNo: Int = 1,

    @Column(nullable = false, length = 32)
    var status: String = "REQUESTED",

    val snapshotId: UUID? = null,

    var retryCount: Int = 0,

    val maxRetries: Int = 3,

    var nextRetryAt: Instant? = null,

    var lockedBy: String? = null,

    var lockedUntil: Instant? = null,

    @Column(length = 64)
    var lastErrorCode: String? = null,

    var errorMessage: String? = null,

    @Column(columnDefinition = "JSONB")
    var calculationResult: String? = null,

    val createdAt: Instant = Instant.now(),

    var updatedAt: Instant = Instant.now(),

    var completedAt: Instant? = null
)
