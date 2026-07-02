package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater

/**
 * Owns every object-storage concern of a snapshot-sink run:
 *  - chunk object keys (`{runKey}/part-NNNNNN.jsonl.gz`)
 *  - failed record key (`{runKey}/failed.jsonl`)
 *  - manifest key (`{runKey}/manifest.json`)
 *  - the [SnapshotChunkManifest] for the run
 *  - the active [GzipJsonlChunkWriter] and rotation
 *
 * Failure records are appended as JSONL lines to the failed-key object
 * using [ObjectStorage] directly. [SnapshotFailedRecordWriter] migration to
 * ObjectStorage is handled in a later task.
 *
 * **Thread-affinity:** NOT thread-safe. All methods must be called from the
 * sink's single writer thread. The class does not perform its own locking.
 *
 * @param runKey  the run key prefix in object storage (e.g. `runs/<runId>/<endpoint>`)
 * @param endpoint the API endpoint name (used for log lines and manifest)
 * @param maxRecords max records per chunk before rotation
 * @param maxUncompressedBytes hard cap per uncompressed chunk
 * @param objectMapper Jackson mapper for manifest and failure lines
 * @param clock injected clock for deterministic timestamps
 * @param objectStorage backend for chunk / manifest / failed-record writes
 */
class ChunkFileManager(
    private val runKey: String,
    private val endpoint: String,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val objectStorage: ObjectStorage,
    private val compressionLevel: Int = Deflater.BEST_SPEED,
) {
    private val log = LoggerFactory.getLogger(ChunkFileManager::class.java)

    private val failedKey: String = "$runKey/failed.jsonl"
    private val manifestKey: String = "$runKey/manifest.json"
    private val successKey: String = "$runKey/_SUCCESS"

    private val failedWriter = SnapshotFailedRecordWriter(
        runKey = runKey,
        objectMapper = objectMapper,
        objectStorage = objectStorage,
    )

    private val manifest = SnapshotChunkManifest(
        runId = runKey.substringAfter("runs/").substringBefore('/'),
        endpoint = endpoint,
        startedAt = Instant.now(clock),
    )

    private var failedCount: Int = 0
    private var currentWriter: GzipJsonlChunkWriter
    private var nextPartIndex = 2

    /**
     * In-flight chunk uploads fired by [rotateChunk] and [closeCurrentChunk].
     * Each entry is a fire-and-forget future returned by
     * [ObjectStorage.putFileAsync]. The sink awaits ALL of these before
     * writing the manifest, to guarantee that every chunk the manifest
     * references is actually present in storage when the manifest is
     * published.
     */
    private val inFlightUploads: MutableList<CompletableFuture<PutResult>> = mutableListOf()

    init {
        currentWriter = newChunkWriter(1)
    }

    fun appendSuccess(record: SnapshotChunkRecord.Success): ChunkStats? {
        currentWriter.append(record)
        manifest.totalRecords++
        if (currentWriter.shouldRotate()) {
            return rotateChunk()
        }
        return null
    }

    /**
     * Append a producer-serialized record. Mirrors [appendSuccess] but skips
     * the Jackson call on the writer thread. See ADR-729.
     */
    fun appendPreSerialized(record: SnapshotChunkRecord.PreSerialized): ChunkStats? {
        currentWriter.appendPreSerialized(record)
        manifest.totalRecords++
        if (currentWriter.shouldRotate()) {
            return rotateChunk()
        }
        return null
    }

    fun appendFailure(record: SnapshotChunkRecord.Failure) {
        failedWriter.append(record)
        failedCount++
    }

    fun rotateChunk(): ChunkStats? {
        val stats = currentWriter.close()
        registerUpload(stats)
        if (stats.recordCount > 0) {
            manifest.chunks.add(toEntry(stats))
        }
        currentWriter = newChunkWriter(nextPartIndex++)
        return stats.takeIf { it.recordCount > 0 }
    }

    fun closeCurrentChunk(): ChunkStats? {
        val stats = currentWriter.close()
        registerUpload(stats)
        if (stats.recordCount > 0) {
            manifest.chunks.add(toEntry(stats))
        }
        return stats.takeIf { it.recordCount > 0 }
    }

    private fun registerUpload(stats: ChunkStats) {
        // [ChunkStats.uploadFuture] is non-null when the writer used the
        // async path. Sync writers (legacy tests, LocalFs when running
        // on the host) may have a completed-future instead. Either way we
        // track and await it before writing the manifest.
        stats.uploadFuture?.let { inFlightUploads.add(it) }
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
        val manifestJson = objectMapper.writeValueAsBytes(manifest)
        objectStorage.put(manifestKey, manifestJson)
        objectStorage.put(successKey, ByteArray(0))
    }

    fun manifest(): SnapshotChunkManifest = manifest

    /**
     * Best-effort cleanup after a failed run. Removes all chunk / manifest / failed-record
     * objects under [runKey] and the `_RUNNING` marker. Called from the sink's failure path.
     */
    fun cleanupOnFailure() {
        objectStorage.deleteByPrefix(runKey)
        objectStorage.delete("$runKey/_RUNNING")
    }

    /** Remove the `_RUNNING` marker after a successful run finalizes. */
    fun deleteRunningMarker() {
        objectStorage.delete("$runKey/_RUNNING")
    }

    private fun newChunkWriter(partIndex: Int): GzipJsonlChunkWriter {
        val chunkKey = "$runKey/chunks/part-${String.format("%06d", partIndex)}.jsonl.gz"
        return GzipJsonlChunkWriter(
            chunkKey = chunkKey,
            partIndex = partIndex,
            maxRecords = maxRecords,
            maxUncompressedBytes = maxUncompressedBytes,
            objectMapper = objectMapper,
            objectStorage = objectStorage,
            clock = clock,
            compressionLevel = compressionLevel,
        )
    }

    private fun toEntry(stats: ChunkStats): ChunkEntry = ChunkEntry(
        path = stats.path,
        recordCount = stats.recordCount,
        uncompressedBytes = stats.uncompressedBytes,
        compressedBytes = stats.compressedBytes,
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
