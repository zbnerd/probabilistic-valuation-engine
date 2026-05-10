package maple.synchronizer.event

import java.time.Instant

data class CalculatorResultChunkReadyEvent(
    val eventId: String,
    val eventType: String,
    val schemaVersion: Int,
    val sourceRunId: String,
    val sourceEndpoint: String,
    val sourceChunkId: String,
    val objectKey: String,
    val sourceRecordCount: Int,
    val resultCount: Int,
    val errorCount: Int,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val createdAt: Instant,
)
