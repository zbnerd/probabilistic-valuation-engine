package maple.common.cleanup

import java.time.Instant

data class RunInfo(
    val runId: String,
    val createdAt: Instant,
    val isRunning: Boolean,
    val sizeBytes: Long,
)
