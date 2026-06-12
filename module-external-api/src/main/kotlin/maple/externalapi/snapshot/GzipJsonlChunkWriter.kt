package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.storage.ObjectStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.UUID
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
 * stored in ObjectStorage under `chunkKey`.
 *
 * Gzip output is written to a temp file on disk (not an in-memory
 * [java.io.ByteArrayOutputStream]) so the writer thread's heap footprint
 * stays bounded by the deflater window (~32KB) regardless of
 * [maxUncompressedBytes]. The previous heap-buffered design OOM'd the
 * 1GB-heap writer thread at `maxUncompressedBytes=128MB` because the
 * intermediate buffer plus `ByteArrayOutputStream.toByteArray()` reached
 * ~256MB before `objectStorage.put` was called.
 *
 * On close(), the temp file is streamed to storage via
 * [ObjectStorage.putStream] and deleted. The two backends
 * ([maple.expectation.infrastructure.storage.LocalFsObjectStorage],
 * [maple.expectation.infrastructure.storage.MinioObjectStorage]) both
 * spool to a temp file internally, so net disk usage during a chunk
 * rotation is at most two copies of the chunk briefly.
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
    private val tempFile: Path = Files.createTempFile(
        "gzip-chunk-${UUID.randomUUID()}-part-${partIndex.toString().padStart(6, '0')}-",
        ".jsonl.gz.tmp",
    )
    private val fileOut = Files.newOutputStream(
        tempFile,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING,
    )
    private val gzipped = GZIPOutputStream(fileOut)
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
        var putSize: Long = 0L
        try {
            gzipped.close()
            fileOut.close()
            Files.newInputStream(tempFile).use { input ->
                val result = objectStorage.putStream(chunkKey, input)
                putSize = result.size
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
        return ChunkStats(
            partIndex = partIndex,
            path = chunkKey.substringAfterLast('/'),
            recordCount = recordCount,
            uncompressedBytes = uncompressedBytes,
            compressedBytes = putSize,
            startedAt = startedAt,
            finishedAt = Instant.now(clock),
        )
    }
}
