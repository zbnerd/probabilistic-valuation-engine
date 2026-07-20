package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import maple.expectation.common.storage.ObjectStorage
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.write.ArtifactReceipt
import maple.pipeline.artifact.write.ArtifactWriter
import org.slf4j.LoggerFactory

/**
 * Owns every object-storage concern of a snapshot-sink run:
 *  - chunk object keys (`runs/{runId}/{endpoint}/chunks/part-NNNNNN.jsonl.gz`)
 *  - failed record key (`runs/{runId}/{endpoint}/failed.jsonl`)
 *  - manifest key (`runs/{runId}/{endpoint}/manifest.json`)
 *  - the [SnapshotChunkManifest] for the run
 *  - the active [GzipJsonlChunkWriter] and rotation
 *
 * Failure records are appended as JSONL lines to the typed failed-key object
 * using [ObjectStorage] directly.
 *
 * **Thread-affinity:** NOT thread-safe. All methods must be called from the
 * sink's single writer thread. The class does not perform its own locking.
 *
 * @param runId the source run identifier
 * @param endpoint the API endpoint name (used for log lines and manifest)
 * @param maxRecords max records per chunk before rotation
 * @param maxUncompressedBytes hard cap per uncompressed chunk
 * @param objectMapper Jackson mapper for manifest and failure lines
 * @param clock injected clock for deterministic timestamps
 * @param objectStorage backend for chunk / manifest / failed-record writes
 */
class ChunkFileManager(
    private val runId: String,
    private val endpoint: String,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val objectStorage: ObjectStorage,
    private val artifactWriter: ArtifactWriter,
) {
    private val log = LoggerFactory.getLogger(ChunkFileManager::class.java)

    private val failedKey: ArtifactKey = SourceArtifactLayout.failedRecords(runId, endpoint)
    private val manifestKey: ArtifactKey = SourceArtifactLayout.manifest(runId, endpoint)

    private val failedWriter = SnapshotFailedRecordWriter(
        failedKey = failedKey,
        objectMapper = objectMapper,
        objectStorage = objectStorage,
    )

    private val manifest = SnapshotChunkManifest(
        runId = runId,
        endpoint = endpoint,
        startedAt = Instant.now(clock),
    )

    private var failedCount: Int = 0
    private var currentWriter: GzipJsonlChunkWriter? = null
    private var nextPartIndex = 2

    /**
     * In-flight chunk uploads fired by [rotateChunk] and [closeCurrentChunk].
     * Each entry completes with an [ArtifactReceipt]. The sink awaits ALL of
     * these before writing the manifest, to guarantee that every referenced
     * chunk is present and its final byte count is known.
     */
    private val inFlightUploads: MutableList<CompletableFuture<ArtifactReceipt>> = mutableListOf()
    private val pendingChunks: MutableList<ChunkStats> = mutableListOf()
    private val registeredChunks: MutableList<ChunkStats> = mutableListOf()
    private val completedReceipts = ConcurrentHashMap<Int, ArtifactReceipt>()

    fun appendSuccess(record: SnapshotChunkRecord.Success): ChunkStats? = appendToCurrent { writer ->
        writer.append(record)
    }

    /**
     * Append a producer-serialized record. Mirrors [appendSuccess] but skips
     * the Jackson call on the writer thread. See ADR-729.
     */
    fun appendPreSerialized(record: SnapshotChunkRecord.PreSerialized): ChunkStats? = appendToCurrent { writer ->
        writer.appendPreSerialized(record)
    }

    fun appendFailure(record: SnapshotChunkRecord.Failure) {
        failedWriter.append(record)
        failedCount++
    }

    fun rotateChunk(): ChunkStats? {
        val writer = currentWriter ?: return null
        currentWriter = null
        val stats = runCatching { writer.close() }.getOrElse { failure ->
            writer.abort(failure)
            throw failure
        }
        val trackedStats = registerUpload(stats)
        currentWriter = newChunkWriter(nextPartIndex++)
        return trackedStats.takeIf { it.recordCount > 0 }
    }

    fun closeCurrentChunk(): ChunkStats? {
        val writer = currentWriter ?: return null
        currentWriter = null
        val stats = runCatching { writer.close() }.getOrElse { failure ->
            writer.abort(failure)
            throw failure
        }
        val trackedStats = registerUpload(stats)
        return trackedStats.takeIf { it.recordCount > 0 }
    }

    /** Abort and evict the active chunk exactly once before failed-run storage cleanup. */
    fun abortCurrentChunk(cause: Throwable): Boolean {
        val writer = currentWriter ?: return false
        currentWriter = null
        writer.abort(cause)
        return true
    }

    private fun registerUpload(stats: ChunkStats): ChunkStats {
        val receiptFuture = stats.uploadFuture.thenApply { receipt ->
            validateReceipt(stats, receipt)
            completedReceipts[stats.partIndex] = receipt
            receipt
        }
        val trackedStats = stats.copy(uploadFuture = receiptFuture)
        registeredChunks.add(trackedStats)
        inFlightUploads.add(receiptFuture)
        if (stats.recordCount > 0) pendingChunks.add(trackedStats)
        return trackedStats
    }

    /**
     * Async variant of [awaitAllUploads]. Returns a [CompletableFuture] that
     * completes with `true` when all in-flight uploads succeed and `false` on
     * timeout or individual upload failure. Never blocks the calling thread
     * — callers chain via `thenCompose` / `whenComplete` to keep the writer
     * thread (or the scheduler's CF chain) free.
     *
     * @param timeoutMs hard timeout (default 10 minutes, matches sync variant)
     */
    fun awaitAllUploadsAsync(
        timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS,
    ): CompletableFuture<Boolean> {
        if (inFlightUploads.isEmpty()) return CompletableFuture.completedFuture(true)
        val all = CompletableFuture.allOf(*inFlightUploads.toTypedArray())
        return all
            .thenApply { true }
            .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .exceptionally { ex ->
                val cause = ex.cause ?: ex
                if (cause is java.util.concurrent.TimeoutException) {
                    log.error(
                        "[ChunkFileManager] awaitAllUploadsAsync timed out after {}ms (in-flight: {})",
                        timeoutMs,
                        inFlightUploads.size,
                    )
                } else {
                    log.error("[ChunkFileManager] awaitAllUploadsAsync failed: {}", cause.message, cause)
                }
                false
            }
    }

    fun inFlightUploadCount(): Int = inFlightUploads.size

    fun finalizeManifestBytes(): ByteArray {
        manifest.totalFailed = failedCount
        manifest.finishedAt = Instant.now(clock)
        manifest.chunks.clear()
        manifest.chunks.addAll(completedChunkEntries())
        return objectMapper.writeValueAsBytes(manifest)
    }

    fun manifest(): SnapshotChunkManifest = manifest

    /**
     * Best-effort cleanup after a failed artifact build. Removes only this
     * manager's chunk objects plus its manifest and failed-record object. The
     * topology-specific `_RUNNING` marker remains for retry/stale-run
     * classification, and `_SUCCESS` remains untouched for publication replay.
     */
    fun cleanupIncompleteArtifacts() {
        val chunkKeys = registeredChunks
            .sortedBy(ChunkStats::partIndex)
            .map { stats -> SourceArtifactLayout.chunk(runId, endpoint, chunkId(stats.partIndex)) }
        val failures = (chunkKeys + manifestKey + failedKey)
            .distinct()
            .mapNotNull { key -> runCatching { objectStorage.delete(key.value) }.exceptionOrNull() }
        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1)
                .filter { failure -> failure !== primary }
                .forEach(primary::addSuppressed)
            throw primary
        }
    }

    private fun newChunkWriter(partIndex: Int): GzipJsonlChunkWriter {
        val chunkKey = SourceArtifactLayout.chunk(runId, endpoint, chunkId(partIndex))
        return GzipJsonlChunkWriter(
            chunkKey = chunkKey,
            partIndex = partIndex,
            maxRecords = maxRecords,
            maxUncompressedBytes = maxUncompressedBytes,
            objectMapper = objectMapper,
            artifactWriter = artifactWriter,
            clock = clock,
        )
    }

    private fun activeWriter(): GzipJsonlChunkWriter = currentWriter ?: newChunkWriter(1).also { writer ->
        currentWriter = writer
    }

    private fun appendToCurrent(append: (GzipJsonlChunkWriter) -> Unit): ChunkStats? {
        val writer = activeWriter()
        return runCatching {
            append(writer)
            manifest.totalRecords++
            if (writer.shouldRotate()) rotateChunk() else null
        }.getOrElse { failure ->
            if (failure is ChunkArtifactWriteException && currentWriter === writer) {
                currentWriter = null
            }
            throw failure
        }
    }

    private fun completedChunkEntries(): List<ChunkEntry> = pendingChunks
        .sortedBy(ChunkStats::partIndex)
        .map { stats -> toEntry(stats, requireNotNull(completedReceipts[stats.partIndex])) }

    private fun validateReceipt(stats: ChunkStats, receipt: ArtifactReceipt) {
        val expectedKey = SourceArtifactLayout.chunk(runId, endpoint, chunkId(stats.partIndex))
        require(receipt.key == expectedKey) {
            "chunk receipt key ${receipt.key.value} does not match expected ${expectedKey.value}"
        }
    }

    private fun chunkId(partIndex: Int): String = "part-${String.format("%06d", partIndex)}"

    private fun toEntry(stats: ChunkStats, receipt: ArtifactReceipt): ChunkEntry = ChunkEntry(
        path = receipt.key.value.substringAfterLast('/'),
        recordCount = stats.recordCount,
        uncompressedBytes = receipt.uncompressedBytes,
        compressedBytes = receipt.compressedBytes,
        startedAt = stats.startedAt,
        finishedAt = stats.finishedAt,
    )

    companion object {
        /**
         * Default hard timeout for [awaitAllUploadsAsync].
         * 10 minutes is generous for 128MB × N chunks on a healthy MinIO.
         */
        const val DEFAULT_AWAIT_TIMEOUT_MS: Long = 600_000L
    }
}
