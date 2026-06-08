package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Owns every filesystem concern of a snapshot-sink run:
 *  - chunk directory layout (chunks/, failed.jsonl, manifest.json, _SUCCESS, _RUNNING)
 *  - the [SnapshotChunkManifest] for the run
 *  - the active [GzipJsonlChunkWriter] and rotation
 *  - the [SnapshotFailedRecordWriter] for failure records
 *
 * **Thread-affinity:** NOT thread-safe. All methods must be called from the
 * sink's single writer thread. The class does not perform its own locking.
 *
 * @param runDir  the run directory (e.g. `runs/<runId>`)
 * @param endpoint the API endpoint name (used as subdirectory)
 * @param maxRecords max records per chunk before rotation
 * @param maxUncompressedBytes hard cap per uncompressed chunk
 * @param objectMapper Jackson mapper for manifest and failure lines
 * @param clock injected clock for deterministic timestamps
 */
class ChunkFileManager(
    private val runDir: Path,
    private val endpoint: String,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ChunkFileManager::class.java)

    val chunksDir: Path = runDir.resolve(endpoint).resolve("chunks")
    private val failedPath: Path = runDir.resolve(endpoint).resolve("failed.jsonl")
    private val manifestPath: Path = runDir.resolve(endpoint).resolve("manifest.json")
    private val successPath: Path = runDir.resolve(endpoint).resolve("_SUCCESS")
    private val runningMarker: Path = runDir.resolve("_RUNNING")

    private val manifest = SnapshotChunkManifest(
        runId = runDir.fileName.toString(),
        endpoint = endpoint,
        startedAt = Instant.now(clock),
    )

    private val failedWriter = SnapshotFailedRecordWriter(failedPath, objectMapper)
    private var currentWriter: GzipJsonlChunkWriter
    private var nextPartIndex = 2

    init {
        Files.createDirectories(chunksDir)
        Files.createDirectories(failedPath.parent)
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

    fun cleanupOnFailure() {
        currentWriter.deleteTmp()
        log.warn("[ChunkFileManager] cleaned up .tmp files after failure")
    }

    fun writeManifestAndSuccessMarker() {
        manifest.totalFailed = failedWriter.count()
        manifest.finishedAt = Instant.now(clock)
        SnapshotChunkManifestWriter(manifestPath, objectMapper).write(manifest)
        Files.writeString(successPath, "")
    }

    fun deleteRunningMarker() {
        if (Files.exists(runningMarker)) {
            Files.delete(runningMarker)
        }
    }

    fun manifest(): SnapshotChunkManifest = manifest

    private fun newChunkWriter(partIndex: Int): GzipJsonlChunkWriter = GzipJsonlChunkWriter(chunksDir, partIndex, maxRecords, maxUncompressedBytes, objectMapper, clock)

    private fun toEntry(stats: ChunkStats): ChunkEntry = ChunkEntry(
        path = stats.path,
        recordCount = stats.recordCount,
        uncompressedBytes = stats.uncompressedBytes,
        compressedBytes = stats.compressedBytes,
        startedAt = stats.startedAt,
        finishedAt = stats.finishedAt,
    )
}
