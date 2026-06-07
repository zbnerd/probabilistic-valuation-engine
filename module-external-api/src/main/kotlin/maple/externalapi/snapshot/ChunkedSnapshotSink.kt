package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
    private val eventPublisher: SnapshotSinkEventPublisher,
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread.ofPlatform().name("snapshot-writer-$endpoint").unstarted(runnable)
    },
    private val clock: Clock = Clock.systemUTC(),
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

    private val writerFuture: Future<*> = writerExecutor.submit {
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
        // monotonic clock, not Clock-injected for perf
        while (System.nanoTime() < deadline) {
            writerError.get()?.let { err ->
                throw IllegalStateException("sink closed due to writer error: ${err.message}", err)
            }
            if (writerFuture.isDone) {
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

        writerExecutor.shutdown()
        if (!writerExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
            log.warn("[Sink] writer executor did not terminate within 60s, forcing shutdown")
            writerExecutor.shutdownNow()
        }

        val err = writerError.get()
        if (err != null) {
            cleanupOnFailure()
            eventPublisher.publishRunFailed(manifest, endpoint, err.message ?: "unknown")
            throw RuntimeException("writer thread failed: ${err.message}", err)
        }

        // close current chunk
        closeCurrentChunk()

        // close failed writer
        manifest.totalFailed = failedWriter.count()

        // write manifest
        manifest.finishedAt = Instant.now(clock)
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
        eventPublisher.publishRunCompleted(manifest, endpoint)
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
            eventPublisher.publishChunkReady(stats, manifest.runId, endpoint)
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
            eventPublisher.publishChunkReady(stats, manifest.runId, endpoint)
        }
    }

    private fun cleanupOnFailure() {
        currentWriter.deleteTmp()
        log.warn("[Sink] cleaned up .tmp files after failure")
    }

    private fun newChunkWriter(partIndex: Int): GzipJsonlChunkWriter =
        GzipJsonlChunkWriter(chunksDir, partIndex, maxRecords, maxUncompressedBytes, objectMapper, clock)
}
