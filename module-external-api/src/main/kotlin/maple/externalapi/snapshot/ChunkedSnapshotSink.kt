package maple.externalapi.snapshot

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

class ChunkedSnapshotSink(
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
        try {
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
                publishWhenUploaded(stats)
            }

            // Wait for all fire-and-forget chunk uploads to complete
            // BEFORE writing the manifest. Otherwise the manifest would
            // reference chunks that haven't arrived in MinIO yet, and the
            // downstream calculator/synchronizer could read incomplete data.
            // 10 minutes is generous for 128MB × N chunks on a healthy
            // MinIO; the ChunkFileManager logs the actual timeout.
            if (!fileManager.awaitAllUploads(600_000L)) {
                // Uploads timed out or failed — fail the run loudly.
                val msg = "chunk uploads did not complete in time (in-flight=${fileManager.inFlightUploadCount()})"
                fileManager.cleanupOnFailure()
                eventPublisher.publishRunFailed(manifest, endpoint, msg)
                throw RuntimeException(msg)
            }

            fileManager.writeManifestAndSuccessMarker()
            fileManager.deleteRunningMarker()

            log.info(
                "[Sink] closed: endpoint={}, chunks={}, records={}, failed={}",
                endpoint,
                manifest.chunks.size,
                manifest.totalRecords,
                manifest.totalFailed,
            )

            // publish run completed (after _SUCCESS)
            eventPublisher.publishRunCompleted(manifest, endpoint)
        } finally {
            // Ensure writerExecutor is fully terminated even if an earlier step throws.
            if (!writerExecutor.isTerminated) {
                writerExecutor.shutdownNow()
            }
        }
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
        } catch (ex: Throwable) {
            // Catch Throwable (not Exception) so heap pressure / VM-level
            // failures (OutOfMemoryError, StackOverflowError, etc.) are
            // recorded in writerError instead of letting the writer thread
            // die silently. Without this, a subsequent submit() would only
            // see writerFuture.isDone = true and throw the vague
            // "sink writer thread is not alive" message, losing the
            // original cause.
            writerError.set(ex)
            accepting.set(false)
            log.error("[Sink] writer thread error: {}", ex.message, ex)
        }
    }

    private fun handleSuccess(record: SnapshotChunkRecord.Success) {
        try {
            val stats = fileManager.appendSuccess(record)
            if (stats != null) {
                publishWhenUploaded(stats)
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

    /**
     * Publish chunk-ready AFTER the underlying PUT completes. Restores the
     * pre-94cdd5685 ordering where publish strictly follows successful upload,
     * but without blocking the writer thread.
     *
     * Why whenComplete (callback) and not join():
     * - whenComplete runs on the transfer-manager's completion thread, NOT
     *   on the writer thread. Writer thread returns immediately after
     *   registering the callback, so per-chunk latency is the gzip+close
     *   time only (~50ms), not gzip+upload.
     * - join() would block the writer for 1-4s per chunk (upload duration),
     *   serializing the writer on the slowest in-flight upload. That's the
     *   throughput cap.
     * - Race is closed by ordering: the publish only fires after the
     *   upload future completes successfully. Calculator's first head()
     *   on the object key will see the chunk in MinIO.
     *
     * If the upload fails, the chunk-ready event is skipped (calculator
     * would 404 anyway). The run-failed path at sink close surfaces the
     * error.
     */
    private fun publishWhenUploaded(stats: ChunkStats) {
        val future = stats.uploadFuture
        if (future == null) {
            eventPublisher.publishChunkReady(stats, fileManager.manifest().runId, endpoint)
            return
        }
        future.whenComplete { _, ex ->
            if (ex != null) {
                log.warn(
                    "[Sink] chunk upload failed, skipping chunk-ready publish: chunk={} error={}",
                    stats.path,
                    ex.message,
                )
                return@whenComplete
            }
            eventPublisher.publishChunkReady(stats, fileManager.manifest().runId, endpoint)
        }
    }
}