package maple.expectation.infrastructure.queue.pgmq

import java.time.Instant

data class NexonApiRequestMessage(
    val jobId: java.util.UUID,
    val ocid: String,
    val userIgn: String,
    val presetNo: Int = 1,
    val eventType: String = "FETCH_EQUIPMENT",
    val requestedAt: String = Instant.now().toString(),
)
