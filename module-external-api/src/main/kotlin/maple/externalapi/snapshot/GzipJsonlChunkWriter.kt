package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.storage.ObjectStorage
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.util.zip.GZIPOutputStream

data class ChunkStats(
    val partIndex: Int,
    val path: String,
    val recordCount: Int,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val startedAt: Instant,
    val finishedAt: Instant,
)

/**
 * Streams SnapshotChunkRecord.Success entries into a gzipped JSONL object
 * stored in ObjectStorage under `chunkKey`. No local temp file; bytes are
 * accumulated in a ByteArrayOutputStream and put on close().
 */
class GzipJsonlChunkWriter(
    private val chunkKey: String,
    private val partIndex: Int,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val objectMapper: ObjectMapper,
    private val objectStorage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val buffer = ByteArrayOutputStream()
    private val gzipped = GZIPOutputStream(buffer)
    private var recordCount: Int = 0
    private var uncompressedBytes: Long = 0
    private val startedAt: Instant = Instant.now(clock)

    fun append(record: SnapshotChunkRecord.Success) {
        require(record.bodyBytes.isNotEmpty()) { "bodyBytes must not be empty for key=${record.key}" }
        val line = objectMapper.writeValueAsBytes(record)
        gzipped.write(line)
        gzipped.write('\n'.code)
        recordCount++
        uncompressedBytes += line.size + 1
    }

    fun shouldRotate(): Boolean =
        recordCount >= maxRecords || uncompressedBytes >= maxUncompressedBytes

    fun close(): ChunkStats {
        gzipped.close()
        val compressedBytes = buffer.toByteArray()
        objectStorage.put(chunkKey, compressedBytes)
        return ChunkStats(
            partIndex = partIndex,
            path = chunkKey.substringAfterLast('/'),
            recordCount = recordCount,
            uncompressedBytes = uncompressedBytes,
            compressedBytes = compressedBytes.size.toLong(),
            startedAt = startedAt,
            finishedAt = Instant.now(clock),
        )
    }
}
