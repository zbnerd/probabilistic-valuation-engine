package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ChunkedSnapshotSink(
    private val runDir: Path,
    private val endpoint: String,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val queueCapacity: Int,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: SnapshotChunkEventPublisher,
    private val volumeMetrics: SnapshotVolumeMetrics,
) {
    private val log = LoggerFactory.getLogger(ChunkedSnapshotSink::class.java)

    private val chunksDir: Path = runDir.resolve(endpoint).resolve("chunks")
    private val failedPath: Path = runDir.resolve(endpoint).resolve("failed.jsonl")
    private val manifestPath: Path = runDir.resolve(endpoint).resolve("manifest.json")
    private val successPath: Path = runDir.resolve(endpoint).resolve("_SUCCESS")

    private val queue = ArrayBlockingQueue<SnapshotChunkRecord>(queueCapacity)
    private val accepting = AtomicBoolean(true)
    private val writerError = AtomicReference<Throwable?>(null)

    private val manifest = SnapshotChunkManifest(
        runId = runDir.fileName.toString(),
        endpoint = endpoint,
        startedAt = Instant.now(),
    )

    private val failedWriter = SnapshotFailedRecordWriter(failedPath, objectMapper)
    private var currentWriter: GzipJsonlChunkWriter
    private var nextPartIndex = 2

    init {
        Files.createDirectories(chunksDir)
        Files.createDirectories(failedPath.parent)
        currentWriter = newChunkWriter(1)
    }

    private val writerThread: Thread = Thread.ofPlatform().name("snapshot-writer-$endpoint").start {
        runWriterLoop()
    }

    fun submit(record: SnapshotChunkRecord) {
        if (!accepting.get()) {
            val err = writerError.get()
            if (err != null) {
                throw IllegalStateException("sink closed due to writer error: ${err.message}", err)
            }
            throw IllegalStateException("sink is closed, cannot submit")
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            writerError.get()?.let { err ->
                throw IllegalStateException("sink closed due to writer error: ${err.message}", err)
            }
            if (!writerThread.isAlive) {
                throw IllegalStateException("sink writer thread is not alive")
            }
            if (queue.offer(record, 100, TimeUnit.MILLISECONDS)) {
                return
            }
        }
        if (!queue.offer(record)) {
            throw IllegalStateException("sink queue full after 30s, likely writer thread stuck")
        }
    }

    fun queueDepth(): Int = queue.size

    fun close() {
        accepting.set(false)
        if (!queue.offer(SnapshotChunkRecord.CloseSignal, 30, java.util.concurrent.TimeUnit.SECONDS)) {
            throw IllegalStateException("failed to enqueue close signal after 30s")
        }

        writerThread.join(60_000)
        if (writerThread.isAlive) {
            log.warn("[Sink] writer thread did not terminate within 60s, interrupting")
            writerThread.interrupt()
        }

        val err = writerError.get()
        if (err != null) {
            cleanupOnFailure()
            publishRunFailed(err.message ?: "unknown")
            throw RuntimeException("writer thread failed: ${err.message}", err)
        }

        // close current chunk
        closeCurrentChunk()

        // close failed writer
        manifest.totalFailed = failedWriter.count()

        // write manifest
        manifest.finishedAt = Instant.now()
        val manifestWriter = SnapshotChunkManifestWriter(manifestPath, objectMapper)
        manifestWriter.write(manifest)

        // create _SUCCESS
        Files.writeString(successPath, "")

        // Remove run-level _RUNNING marker (if exists)
        val runningMarker = runDir.resolve("_RUNNING")
        if (Files.exists(runningMarker)) {
            Files.delete(runningMarker)
        }

        log.info(
            "[Sink] closed: endpoint={}, chunks={}, records={}, failed={}",
            endpoint, manifest.chunks.size, manifest.totalRecords, manifest.totalFailed,
        )

        // publish run completed (after _SUCCESS)
        publishRunCompleted()
    }

    private fun runWriterLoop() {
        try {
            while (true) {
                val record = queue.take()
                when (record) {
                    is SnapshotChunkRecord.Success -> handleSuccess(record)
                    is SnapshotChunkRecord.Failure -> failedWriter.append(record)
                    is SnapshotChunkRecord.CloseSignal -> return
                }
            }
        } catch (ex: Exception) {
            writerError.set(ex)
            accepting.set(false)
            log.error("[Sink] writer thread error: {}", ex.message, ex)
        }
    }

    private fun handleSuccess(record: SnapshotChunkRecord.Success) {
        try {
            currentWriter.append(record)
            manifest.totalRecords++

            if (currentWriter.shouldRotate()) {
                rotateChunk()
            }
        } catch (ex: Exception) {
            log.warn("[Sink] invalid bodyBytes for key={}, treating as failure: {}", record.key, ex.message)
            failedWriter.append(
                SnapshotChunkRecord.Failure(
                    key = record.key,
                    endpoint = record.endpoint,
                    keyType = record.keyType,
                    httpStatus = record.httpStatus,
                    fetchedAt = record.fetchedAt,
                    errorMessage = "invalid body: ${ex.message}",
                ),
            )
        }
    }

    private fun rotateChunk() {
        val stats = currentWriter.close()
        if (stats.recordCount > 0) {
            val entry = ChunkEntry(
                path = stats.path,
                recordCount = stats.recordCount,
                uncompressedBytes = stats.uncompressedBytes,
                compressedBytes = stats.compressedBytes,
                startedAt = stats.startedAt,
                finishedAt = stats.finishedAt,
            )
            manifest.chunks.add(entry)
            publishChunkReady(stats)
        }
        currentWriter = newChunkWriter(nextPartIndex++)
    }

    private fun closeCurrentChunk() {
        val stats = currentWriter.close()
        if (stats.recordCount > 0) {
            val entry = ChunkEntry(
                path = stats.path,
                recordCount = stats.recordCount,
                uncompressedBytes = stats.uncompressedBytes,
                compressedBytes = stats.compressedBytes,
                startedAt = stats.startedAt,
                finishedAt = stats.finishedAt,
            )
            manifest.chunks.add(entry)
            publishChunkReady(stats)
        }
    }

    private fun cleanupOnFailure() {
        currentWriter.deleteTmp()
        log.warn("[Sink] cleaned up .tmp files after failure")
    }

    private fun newChunkWriter(partIndex: Int): GzipJsonlChunkWriter =
        GzipJsonlChunkWriter(chunksDir, partIndex, maxRecords, maxUncompressedBytes, objectMapper)

    private fun objectKeyFor(stats: ChunkStats): String =
        "runs/${manifest.runId}/${endpoint}/${stats.path}"

    private fun publishChunkReady(stats: ChunkStats) {
        val chunkId = String.format("part-%06d", stats.partIndex)
        val ratio = if (stats.compressedBytes > 0) "%.2f".format(stats.uncompressedBytes.toDouble() / stats.compressedBytes.toDouble()) else "N/A"
        volumeMetrics.recordChunk(stats.compressedBytes, stats.uncompressedBytes, stats.recordCount.toLong())
        log.info(
            "[snapshotVolume] runId={} chunkId={} compressedBytes={} uncompressedBytes={} jsonRows={} compressionRatio={}",
            manifest.runId, chunkId, stats.compressedBytes, stats.uncompressedBytes, stats.recordCount, ratio,
        )

        val event = SnapshotChunkReadyEvent(
            eventId = UUID.randomUUID().toString(),
            runId = manifest.runId,
            endpoint = endpoint,
            chunkId = chunkId,
            objectKey = objectKeyFor(stats),
            recordCount = stats.recordCount,
            uncompressedBytes = stats.uncompressedBytes,
            compressedBytes = stats.compressedBytes,
            createdAt = Instant.now(),
        )
        try {
            eventPublisher.publishChunkReady(event)
                .exceptionally { ex ->
                    log.warn("[Sink] failed to publish chunk-ready event for chunkId={}: {}", event.chunkId, ex.message)
                    null
                }
        } catch (ex: Exception) {
            log.warn("[Sink] failed to publish chunk-ready event for chunkId={}: {}", event.chunkId, ex.message)
        }
    }

    private fun publishRunCompleted() {
        val event = SnapshotRunCompletedEvent(
            eventId = UUID.randomUUID().toString(),
            runId = manifest.runId,
            endpoint = endpoint,
            manifestPath = "runs/${manifest.runId}/${endpoint}/manifest.json",
            totalRecords = manifest.totalRecords,
            totalFailed = manifest.totalFailed,
            chunkCount = manifest.chunks.size,
            startedAt = manifest.startedAt,
            finishedAt = requireNotNull(manifest.finishedAt),
            createdAt = Instant.now(),
        )
        try {
            eventPublisher.publishRunCompleted(event)
                .exceptionally { ex ->
                    log.warn("[Sink] failed to publish run-completed event for runId={}: {}", manifest.runId, ex.message)
                    null
                }
        } catch (ex: Exception) {
            log.warn("[Sink] failed to publish run-completed event for runId={}: {}", manifest.runId, ex.message)
        }
    }

    private fun publishRunFailed(errorMessage: String) {
        val event = SnapshotRunFailedEvent(
            eventId = UUID.randomUUID().toString(),
            runId = manifest.runId,
            endpoint = endpoint,
            errorMessage = errorMessage,
            createdAt = Instant.now(),
        )
        try {
            eventPublisher.publishRunFailed(event)
                .exceptionally { ex ->
                    log.warn("[Sink] failed to publish run-failed event for runId={}: {}", manifest.runId, ex.message)
                    null
                }
        } catch (ex: Exception) {
            log.warn("[Sink] failed to publish run-failed event for runId={}: {}", manifest.runId, ex.message)
        }
    }
}
