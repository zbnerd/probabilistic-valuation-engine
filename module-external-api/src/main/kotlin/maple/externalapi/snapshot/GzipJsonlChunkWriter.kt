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
 * On close(), the temp file is uploaded to storage via
 * [ObjectStorage.putFile] — the AWS SDK (MinIO) streams from the Path in
 * 8MB chunks; the LocalFs backend atomically renames the file in place.
 * Both backends avoid the double-spool of the original [ObjectStorage.putStream]
 * impl, which copied the input into a SECOND temp file before uploading —
 * a 128MB disk write + read + delete per chunk, making the writer the
 * bottleneck at ~50 files/s instead of the expected ~150.
 *
 * File ownership: the writer deletes the temp file only on the failure
 * path. On success, [ObjectStorage.putFile] takes ownership and the file
 * is moved/atomically-renamed to the destination.
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
    private var closed: Boolean = false

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
        require(!closed) { "close() already called" }
        closed = true
        gzipped.close()
        fileOut.close()
        val result = try {
            objectStorage.putFile(chunkKey, tempFile)
        } catch (t: Throwable) {
            // Storage failed — temp file is still ours to clean up.
            Files.deleteIfExists(tempFile)
            throw t
        }
        // Success path: storage took ownership via atomic move (Local) or
        // upload (MinIO). Either way, the temp file at the original path
        // no longer exists — try delete (no-op if so).
        Files.deleteIfExists(tempFile)
        return ChunkStats(
            partIndex = partIndex,
            path = chunkKey.substringAfterLast('/'),
            recordCount = recordCount,
            uncompressedBytes = uncompressedBytes,
            compressedBytes = result.size,
            startedAt = startedAt,
            finishedAt = Instant.now(clock),
        )
    }
}
