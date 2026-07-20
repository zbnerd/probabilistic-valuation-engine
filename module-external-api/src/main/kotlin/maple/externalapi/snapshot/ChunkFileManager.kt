package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
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

    private val endpointRoot: ArtifactKey = SourceArtifactLayout.endpointRoot(runId, endpoint)
    private val failedKey: ArtifactKey = SourceArtifactLayout.failedRecords(runId, endpoint)
    private val manifestKey: ArtifactKey = SourceArtifactLayout.manifest(runId, endpoint)
    private val successKey: ArtifactKey = SourceArtifactLayout.endpointSuccess(runId, endpoint)
    private val runningKey: ArtifactKey = SourceArtifactLayout.endpointRunning(runId, endpoint)

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

    fun appendSuccess(record: SnapshotChunkRecord.Success): ChunkStats? {
        val writer = activeWriter()
        writer.append(record)
        manifest.totalRecords++
        if (writer.shouldRotate()) {
            return rotateChunk()
        }
        return null
    }

    /**
     * Append a producer-serialized record. Mirrors [appendSuccess] but skips
     * the Jackson call on the writer thread. See ADR-729.
     */
    fun appendPreSerialized(record: SnapshotChunkRecord.PreSerialized): ChunkStats? {
        val writer = activeWriter()
        writer.appendPreSerialized(record)
        manifest.totalRecords++
        if (writer.shouldRotate()) {
            return rotateChunk()
        }
        return null
    }

    fun appendFailure(record: SnapshotChunkRecord.Failure) {
        failedWriter.append(record)
        failedCount++
    }

    fun rotateChunk(): ChunkStats? {
        val writer = currentWriter ?: return null
        val stats = writer.close()
        registerUpload(stats)
        if (stats.recordCount > 0) {
            pendingChunks.add(stats)
        }
        currentWriter = newChunkWriter(nextPartIndex++)
        return stats.takeIf { it.recordCount > 0 }
    }

    fun closeCurrentChunk(): ChunkStats? {
        val writer = currentWriter ?: return null
        val stats = writer.close()
        currentWriter = null
        registerUpload(stats)
        if (stats.recordCount > 0) {
            pendingChunks.add(stats)
        }
        return stats.takeIf { it.recordCount > 0 }
    }

    private fun registerUpload(stats: ChunkStats) {
        inFlightUploads.add(stats.uploadFuture)
    }

    /**
     * Block until all in-flight chunk uploads complete, with a hard timeout
     * to avoid hanging the sink close forever on a stuck MinIO. Returns
     * `true` on success, `false` on timeout. Errors raised by individual
     * uploads are logged but not rethrown — the sink's run-completed event
     * is the place to surface them.
     */
    fun awaitAllUploads(timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS): Boolean {
        if (inFlightUploads.isEmpty()) return true
        val all = CompletableFuture.allOf(*inFlightUploads.toTypedArray())
        return try {
            all.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (ex: java.util.concurrent.TimeoutException) {
            log.error(
                "[ChunkFileManager] awaitAllUploads timed out after {}ms (in-flight: {})",
                timeoutMs,
                inFlightUploads.size,
            )
            false
        } catch (ex: Exception) {
            // At least one upload failed. The first failing future's
            // exception is wrapped in ExecutionException; we just want
            // a non-zero return so the sink can decide whether to fail-fast.
            log.error("[ChunkFileManager] awaitAllUploads failed: {}", ex.message, ex)
            false
        }
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

    fun writeManifestAndSuccessMarker() {
        manifest.totalFailed = failedCount
        manifest.finishedAt = Instant.now(clock)
        manifest.chunks.clear()
        manifest.chunks.addAll(completedChunkEntries())
        val manifestJson = objectMapper.writeValueAsBytes(manifest)
        objectStorage.put(manifestKey.value, manifestJson)
        objectStorage.put(successKey.value, ByteArray(0))
    }

    fun manifest(): SnapshotChunkManifest = manifest

    /**
     * Best-effort cleanup after a failed run. Removes all chunk / manifest / failed-record
     * objects under the typed endpoint root and the `_RUNNING` marker. Called from the sink's failure path.
     */
    fun cleanupOnFailure() {
        objectStorage.deleteByPrefix(endpointRoot.value)
        objectStorage.delete(runningKey.value)
    }

    /** Remove the `_RUNNING` marker after a successful run finalizes. */
    fun deleteRunningMarker() {
        objectStorage.delete(runningKey.value)
    }

    private fun newChunkWriter(partIndex: Int): GzipJsonlChunkWriter {
        val chunkId = "part-${String.format("%06d", partIndex)}"
        val chunkKey = SourceArtifactLayout.chunk(runId, endpoint, chunkId)
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

    private fun completedChunkEntries(): List<ChunkEntry> = pendingChunks
        .sortedBy(ChunkStats::partIndex)
        .map { stats -> toEntry(stats, stats.uploadFuture.resultNow()) }

    private fun toEntry(stats: ChunkStats, receipt: ArtifactReceipt): ChunkEntry = ChunkEntry(
        path = stats.path,
        recordCount = stats.recordCount,
        uncompressedBytes = stats.uncompressedBytes,
        compressedBytes = receipt.compressedBytes,
        startedAt = stats.startedAt,
        finishedAt = stats.finishedAt,
    )

    companion object {
        /**
         * Default hard timeout for [awaitAllUploads] / [awaitAllUploadsAsync].
         * 10 minutes is generous for 128MB × N chunks on a healthy MinIO.
         */
        const val DEFAULT_AWAIT_TIMEOUT_MS: Long = 600_000L
    }
}
