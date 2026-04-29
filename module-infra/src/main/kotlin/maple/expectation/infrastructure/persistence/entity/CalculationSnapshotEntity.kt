package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "calculation_snapshots")
open class CalculationSnapshotEntity(

    @Id
    @Column(updatable = false, nullable = false)
    val snapshotId: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val jobId: UUID,

    @Column(nullable = false, length = 512)
    val objectKey: String,

    @Column(nullable = false, length = 16)
    val storageType: String = "LOCAL",

    @Column(length = 64)
    val characterId: String? = null,

    val presetNo: Int = 1,

    val compressedSize: Long? = null,

    val originalSize: Long? = null,

    @Column(length = 128)
    val hash: String? = null,

    @Column(nullable = false)
    val expiresAt: Instant,

    val createdAt: Instant = Instant.now(),
)
