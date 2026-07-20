package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.slf4j.LoggerFactory

data class ChunkStats(
    val partIndex: Int,
    val path: String,
    val recordCount: Int,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val startedAt: Instant,
    val finishedAt: Instant,
    /**
     * Fire-and-forget upload future. `null` for the legacy sync `putFile`
     * path. The storage backend borrows the writer-owned source file until
     * this future completes. The caller must eventually `await()` it to
     * surface upload errors before considering the run complete.
     */
    val uploadFuture: CompletableFuture<PutResult>? = null,
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
 * [ObjectStorage.putFileAsync] — returns immediately with a
 * [CompletableFuture]. Multiple 128MB chunks overlap their uploads via the
 * [software.amazon.awssdk.transfer.s3.S3TransferManager] thread pool, so
 * the writer thread is no longer blocked on the slowest upload. The
 * caller (typically [ChunkFileManager]) is responsible for awaiting all
 * in-flight uploads before writing the manifest, to ensure all chunks
 * are present in storage when the manifest is published.
 *
 * The compressed size ([ChunkStats.compressedBytes]) is read from the
 * temp file at close time (post-gzip) so manifest and event payloads
 * have accurate byte counts without waiting for the upload to finish.
 *
 * File ownership: this writer owns the temp file while
 * [ObjectStorage.putFileAsync] borrows it. The completion callback deletes
 * it after success and retains it after failure for inspection; storage
 * never moves or deletes the caller-owned source.
 */
class GzipJsonlChunkWriter(
    private val chunkKey: String,
    private val partIndex: Int,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val objectMapper: ObjectMapper,
    private val objectStorage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Deflater compression level for the gzip stream. Defaults to
     * [Deflater.BEST_SPEED] (level 1): the writer thread is a single-threaded
     * CPU-bound gzip stage on the snapshot hot path, and large payloads
     * (item-equipment ~218KB avg) make level-6 deflate the throughput ceiling.
     * Level 1 is ~2-3x faster for ~15% larger output; downstream consumers
     * (calculator/synchronizer) read gzip transparently regardless of level.
     * See [docs/05_Reports/05_06_Load_Tests/ENDURANCE_THROUGHPUT_CEILING_20260702.md].
     */
    private val compressionLevel: Int = Deflater.BEST_SPEED,
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

    // GZIPOutputStream has no level constructor; subclass to set the
    // underlying Deflater's level (protected `def` field) after the gzip
    // header is written. setLevel applies to all subsequent deflate calls.
    private val gzipped: GZIPOutputStream = object : GZIPOutputStream(fileOut) {
        init {
            def.setLevel(compressionLevel)
        }
    }
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

    /**
     * Append a producer-serialized JSON line. Caller has already invoked
     * `ObjectMapper.writeValueAsBytes` on the equivalent [SnapshotChunkRecord.Success]
     * and appended a trailing newline. The writer thread skips Jackson.
     * See ADR-729.
     */
    fun appendPreSerialized(record: SnapshotChunkRecord.PreSerialized) {
        require(record.bodyBytes.isNotEmpty()) { "bodyBytes must not be empty for key=${record.key}" }
        gzipped.write(record.bodyBytes)
        recordCount++
        uncompressedBytes += record.bodyBytes.size
    }

    fun shouldRotate(): Boolean = recordCount >= maxRecords || uncompressedBytes >= maxUncompressedBytes

    fun close(): ChunkStats {
        require(!closed) { "close() already called" }
        closed = true
        gzipped.close()
        fileOut.close()
        // Read compressed size BEFORE the upload starts — temp file is
        // finalised (gzip stream closed) so Files.size is accurate.
        // This lets the manifest and chunk-ready events report the right
        // byte counts without awaiting the upload.
        val compressedSize = Files.size(tempFile)
        val uploadFuture = try {
            objectStorage.putFileAsync(chunkKey, tempFile)
        } catch (t: Throwable) {
            // Storage rejected the upload synchronously (e.g. missing
            // bucket, invalid path). The future is never registered with
            // the file manager, so we MUST clean up the temp file here.
            Files.deleteIfExists(tempFile)
            throw t
        }
        // Storage borrows the immutable temp file until this future
        // completes. The writer then deletes its source after success; on
        // failure it remains on disk for inspection.
        uploadFuture.whenComplete { result, ex ->
            if (ex == null) {
                Files.deleteIfExists(tempFile)
            } else {
                // Upload failed — leave the temp file for ops to inspect.
                // ChunkFileManager records the failure via the in-flight
                // future that completes exceptionally.
                log.warn(
                    "[ChunkWriter] upload failed for chunk={}: {}",
                    chunkKey,
                    ex.message,
                )
            }
        }
        return ChunkStats(
            partIndex = partIndex,
            path = chunkKey.substringAfterLast('/'),
            recordCount = recordCount,
            uncompressedBytes = uncompressedBytes,
            compressedBytes = compressedSize,
            startedAt = startedAt,
            finishedAt = Instant.now(clock),
            uploadFuture = uploadFuture,
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(GzipJsonlChunkWriter::class.java)
    }
}
