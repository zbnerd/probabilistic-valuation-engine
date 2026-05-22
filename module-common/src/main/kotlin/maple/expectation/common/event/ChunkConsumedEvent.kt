package maple.expectation.common.event

import java.time.Instant
import java.util.UUID

data class ChunkConsumedEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String = "CHUNK_CONSUMED",
    val schemaVersion: Int = 1,
    val runId: String,
    val endpoint: String,
    val chunkId: String,
    val objectKey: String,
    val sourceObjectKey: String? = null,
    val consumedAt: Instant = Instant.now(),
) {
    fun kafkaKey(): String = "$runId:$endpoint:$chunkId"
}
