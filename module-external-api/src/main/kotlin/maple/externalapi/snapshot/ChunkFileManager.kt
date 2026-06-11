package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.storage.ObjectStorage
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory

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

    fun appendFailure(record: SnapshotChunkRecord.Failure) {
        failedWriter.append(record)
        failedCount++
    }

    fun rotateChunk(): ChunkStats? {
        val stats = currentWriter.close()
        if (stats.recordCount > 0) {
            manifest.chunks.add(toEntry(stats))
        }
        currentWriter = newChunkWriter(nextPartIndex++)
        return stats.takeIf { it.recordCount > 0 }
    }

    fun closeCurrentChunk(): ChunkStats? {
        val stats = currentWriter.close()
        if (stats.recordCount > 0) {
            manifest.chunks.add(toEntry(stats))
        }
        return stats.takeIf { it.recordCount > 0 }
    }

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
}
