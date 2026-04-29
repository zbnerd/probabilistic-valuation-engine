package maple.expectation.infrastructure.queue.pgmq

import java.time.Instant

data class OcidResolveMessage(
    val jobId: java.util.UUID,
    val userIgn: String,
    val presetNo: Int = 1,
    val requestedAt: String = Instant.now().toString(),
)
