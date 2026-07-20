package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.write.ArtifactReceipt
import maple.pipeline.artifact.write.ArtifactWriter
import maple.pipeline.artifact.write.GzipArtifactSession

data class ChunkStats(
    val partIndex: Int,
    val path: String,
    val recordCount: Int,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val startedAt: Instant,
    val finishedAt: Instant,
    val uploadFuture: CompletableFuture<ArtifactReceipt>,
)

/**
 * Streams SnapshotChunkRecord.Success entries into a gzipped JSONL object
 * stored under [chunkKey].
 *
 * Gzip output is written to a temp file on disk (not an in-memory
 * [java.io.ByteArrayOutputStream]) so the writer thread's heap footprint
 * stays bounded by the deflater window (~32KB) regardless of
 * [maxUncompressedBytes]. The previous heap-buffered design OOM'd the
 * 1GB-heap writer thread at `maxUncompressedBytes=128MB` because the
 * intermediate buffer plus `ByteArrayOutputStream.toByteArray()` reached
 * ~256MB before `objectStorage.put` was called.
 *
 * On close(), the writer-owned session returns an upload [CompletableFuture].
 * Multiple 128MB chunks overlap their uploads via the
 * [software.amazon.awssdk.transfer.s3.S3TransferManager] thread pool, so
 * the writer thread is no longer blocked on the slowest upload. The
 * caller (typically [ChunkFileManager]) is responsible for awaiting all
 * in-flight uploads before writing the manifest, to ensure all chunks
 * are present in storage when the manifest is published.
 *
 * Temp-file, gzip, digest, upload, and cleanup ownership all stay inside
 * [ArtifactWriter].
 */
class GzipJsonlChunkWriter(
    private val chunkKey: ArtifactKey,
    private val partIndex: Int,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val objectMapper: ObjectMapper,
    private val artifactWriter: ArtifactWriter,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var session: GzipArtifactSession? = null
    private var recordCount: Int = 0
    private var uncompressedBytes: Long = 0
    private val startedAt: Instant = Instant.now(clock)
    private var closed: Boolean = false

    fun append(record: SnapshotChunkRecord.Success) {
        require(record.bodyBytes.isNotEmpty()) { "bodyBytes must not be empty for key=${record.key}" }
        // Serialization happens before the session output is touched. Let a
        // rejected record propagate without aborting the otherwise-valid
        // current chunk so the sink can record the failure and keep writing.
        val line = objectMapper.writeValueAsBytes(record)
        writeLine(line, trailingNewline = true)
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
        writeLine(record.bodyBytes, trailingNewline = false)
        recordCount++
        uncompressedBytes += record.bodyBytes.size
    }

    fun shouldRotate(): Boolean = recordCount >= maxRecords || uncompressedBytes >= maxUncompressedBytes

    fun close(): ChunkStats {
        require(!closed) { "close() already called" }
        closed = true
        val uploadFuture = activeSession().complete(uncompressedBytes)
        return ChunkStats(
            partIndex = partIndex,
            path = chunkKey.value.substringAfterLast('/'),
            recordCount = recordCount,
            uncompressedBytes = uncompressedBytes,
            compressedBytes = UNKNOWN_COMPRESSED_BYTES,
            startedAt = startedAt,
            finishedAt = Instant.now(clock),
            uploadFuture = uploadFuture,
        )
    }

    private fun writeLine(bytes: ByteArray, trailingNewline: Boolean) {
        writeBytes(bytes)
        if (trailingNewline) writeNewline()
    }

    private fun writeBytes(bytes: ByteArray) = runCatching { activeSession().output.write(bytes) }
        .getOrElse(::abortAndThrow)

    private fun writeNewline() = runCatching { activeSession().output.write('\n'.code) }
        .getOrElse(::abortAndThrow)

    private fun abortAndThrow(failure: Throwable): Nothing {
        session?.abort(failure)
        throw failure
    }

    private fun activeSession(): GzipArtifactSession = session ?: artifactWriter.openGzip(chunkKey).also { opened ->
        session = opened
    }

    private companion object {
        const val UNKNOWN_COMPRESSED_BYTES: Long = -1L
    }
}
