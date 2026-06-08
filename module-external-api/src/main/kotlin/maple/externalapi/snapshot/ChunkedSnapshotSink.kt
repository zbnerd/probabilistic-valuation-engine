package maple.externalapi.snapshot

import org.slf4j.LoggerFactory
import java.nio.file.Path
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
    private val queueCapacity: Int,
    private val fileManager: ChunkFileManager,
    private val eventPublisher: SnapshotSinkEventPublisher,
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread.ofPlatform().name("snapshot-writer-$endpoint").unstarted(runnable)
    },
) {
    private val log = LoggerFactory.getLogger(ChunkedSnapshotSink::class.java)

    private val queue = ArrayBlockingQueue<SnapshotChunkRecord>(queueCapacity)
    private val accepting = AtomicBoolean(true)
    private val writerError = AtomicReference<Throwable?>(null)

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
        val manifest = fileManager.manifest()
        if (err != null) {
            fileManager.cleanupOnFailure()
            eventPublisher.publishRunFailed(manifest, endpoint, err.message ?: "unknown")
            throw RuntimeException("writer thread failed: ${err.message}", err)
        }

        // close current chunk and write manifest + _SUCCESS marker
        fileManager.closeCurrentChunk()?.let { stats ->
            eventPublisher.publishChunkReady(stats, manifest.runId, endpoint)
        }
        fileManager.writeManifestAndSuccessMarker()
        fileManager.deleteRunningMarker()

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
                    is SnapshotChunkRecord.Failure -> fileManager.appendFailure(record)
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
            val stats = fileManager.appendSuccess(record)
            if (stats != null) {
                eventPublisher.publishChunkReady(stats, fileManager.manifest().runId, endpoint)
            }
        } catch (ex: Exception) {
            log.warn("[Sink] invalid bodyBytes for key={}, treating as failure: {}", record.key, ex.message)
            fileManager.appendFailure(
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
}
