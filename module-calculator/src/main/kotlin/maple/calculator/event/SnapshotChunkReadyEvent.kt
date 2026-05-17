package maple.calculator.event

import java.time.Instant

data class SnapshotChunkReadyEvent(
    val eventId: String,
    val eventType: String = "SNAPSHOT_CHUNK_READY",
    val schemaVersion: Int = 1,
    val runId: String,
    val endpoint: String,
    val chunkId: String,
    val objectKey: String,
    val recordCount: Int,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val sha256: String? = null,
    val createdAt: Instant,
)
