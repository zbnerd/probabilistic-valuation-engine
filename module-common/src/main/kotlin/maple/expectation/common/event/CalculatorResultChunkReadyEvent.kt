package maple.expectation.common.event

import java.time.Instant
import java.util.UUID

data class CalculatorResultChunkReadyEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String = "CALCULATOR_RESULT_CHUNK_READY",
    val schemaVersion: Int = 1,
    val sourceRunId: String,
    val sourceEndpoint: String,
    val sourceChunkId: String,
    val objectKey: String,
    val sourceRecordCount: Int,
    val resultCount: Int,
    val errorCount: Int,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val createdAt: Instant = Instant.now(),
)
