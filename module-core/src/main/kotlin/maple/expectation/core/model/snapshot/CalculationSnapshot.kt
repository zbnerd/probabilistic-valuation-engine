package maple.expectation.core.model.snapshot

import java.time.Instant
import java.util.UUID

data class CalculationSnapshot(
    val snapshotId: UUID,
    val jobId: UUID,
    val objectKey: String,
    val storageType: String = "LOCAL",
    val characterId: String? = null,
    val presetNo: Int = 1,
    val compressedSize: Long? = null,
    val originalSize: Long? = null,
    val hash: String? = null,
    val expiresAt: Instant,
    val createdAt: Instant = Instant.now(),
)
