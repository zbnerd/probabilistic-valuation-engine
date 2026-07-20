package maple.externalapi.snapshot

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
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

    /**
     * Dedicated executor for async-orchestration waits in [closeAsync] (e.g.
     * `awaitTermination`). Kept separate from [writerExecutor] because once
     * `writerExecutor.shutdown()` is called it rejects new tasks, and we need
     * a still-live executor to host the post-shutdown wait task.
     */
    private val closeAsyncExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread.ofPlatform().name("snapshot-close-async-$endpoint").unstarted(runnable)
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

    /**
     * Convenience overload for [SnapshotChunkRecord.PreSerialized]. Identical
     * queue + deadline logic; distinct entry point so the writer-loop's
     * `when` branch is exhaustive without a shared base type. See ADR-729.
     */
    fun submitPreSerialized(record: SnapshotChunkRecord.PreSerialized) {
        submit(record)
    }

    fun queueDepth(): Int = queue.size

    @Deprecated("Use closeAsync() for non-blocking shutdown; this sync variant holds the calling thread for up to ~10 minutes")
    fun close() {
        accepting.set(false)
        val closeFailure = runCatching { closeSynchronously() }.exceptionOrNull()
        val terminationFailure = runCatching { ensureWriterExecutorTerminated() }.exceptionOrNull()
        val failure = combineFailures(closeFailure, terminationFailure)
        if (failure != null) {
            handleRunFailure(failure)
            throw failure
        }
    }

    private fun closeSynchronously() {
        if (!queue.offer(SnapshotChunkRecord.CloseSignal, 30, TimeUnit.SECONDS)) {
            throw IllegalStateException("failed to enqueue close signal after 30s")
        }

        writerExecutor.shutdown()
        if (!writerExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
            log.warn("[Sink] writer executor did not terminate within 60s, forcing shutdown")
            writerExecutor.shutdownNow()
        }

        writerError.get()?.let { error ->
            throw RuntimeException("writer thread failed: ${error.message}", error)
        }

        fileManager.closeCurrentChunk()?.let(::publishWhenUploaded)
        if (!fileManager.awaitAllUploads(ChunkFileManager.DEFAULT_AWAIT_TIMEOUT_MS)) {
            throw RuntimeException(
                "chunk uploads did not complete in time (in-flight=${fileManager.inFlightUploadCount()})",
            )
        }

        val manifest = fileManager.manifest()
        fileManager.writeManifestAndSuccessMarker()
        fileManager.deleteRunningMarker()
        logClosed(manifest)
        eventPublisher.publishRunCompleted(manifest, endpoint)
    }

    /**
     * Async variant of [close]. Performs the same work — shutdown writer, close
     * current chunk, await in-flight uploads, write manifest, publish run event —
     * but chains via [CompletableFuture] so the calling thread is never blocked.
     *
     * Audit reference: docs/05_Reports/2026-06-18-blocking-audit.md line 69.
     * Replaces the blocking `all.get(600_000L, TimeUnit.MILLISECONDS)` in the
     * previous sync path.
     *
     * @return CF that completes normally on successful close, exceptionally on
     *         any failure (writer error, upload timeout, manifest write error).
     */
    fun closeAsync(): CompletableFuture<Void> {
        accepting.set(false)
        // Enqueue CloseSignal from a non-writer thread. The writer thread is
        // already blocked in queue.take() waiting for the signal — using the
        // single-thread writerExecutor to enqueue it would deadlock the writer
        // against itself. closeAsyncExecutor hosts the offer and the post-shutdown
        // awaitTermination wait.
        val closeSignalFuture = CompletableFuture.runAsync(
            {
                if (!queue.offer(SnapshotChunkRecord.CloseSignal, 30, TimeUnit.SECONDS)) {
                    throw IllegalStateException("failed to enqueue close signal after 30s")
                }
            },
            closeAsyncExecutor,
        )

        val writerDoneFuture = closeSignalFuture.thenCompose {
            writerExecutor.shutdown()
            CompletableFuture.runAsync(
                {
                    if (!writerExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                        log.warn("[Sink] writer executor did not terminate within 60s, forcing shutdown")
                        writerExecutor.shutdownNow()
                    }
                },
                closeAsyncExecutor,
            )
        }

        val closePipeline = writerDoneFuture
            .thenCompose {
                val err = writerError.get()
                val manifest = fileManager.manifest()
                if (err != null) {
                    return@thenCompose CompletableFuture.failedFuture(
                        RuntimeException("writer thread failed: ${err.message}", err),
                    )
                }

                // Close current chunk and register its upload.
                fileManager.closeCurrentChunk()?.let { stats -> publishWhenUploaded(stats) }

                // Wait for all fire-and-forget chunk uploads to complete
                // BEFORE writing the manifest (otherwise manifest references
                // chunks not yet in MinIO — calculator/sync could read incomplete data).
                fileManager.awaitAllUploadsAsync(ChunkFileManager.DEFAULT_AWAIT_TIMEOUT_MS).thenCompose { allUploaded ->
                    if (!allUploaded) {
                        val msg = "chunk uploads did not complete in time (in-flight=${fileManager.inFlightUploadCount()})"
                        return@thenCompose CompletableFuture.failedFuture(RuntimeException(msg))
                    }

                    fileManager.writeManifestAndSuccessMarker()
                    fileManager.deleteRunningMarker()

                    logClosed(manifest)
                    eventPublisher.publishRunCompleted(manifest, endpoint)
                    CompletableFuture.completedFuture<Void>(null)
                }
            }
        val lifetimeFuture = closePipeline.whenComplete { _, closeError ->
            val closeFailure = closeError?.let(::unwrapCompletionFailure)
            val terminationFailure = runCatching { ensureWriterExecutorTerminated() }.exceptionOrNull()
            val failure = combineFailures(closeFailure, terminationFailure)
            if (failure != null) {
                handleRunFailure(failure)
            }
            closeAsyncExecutor.shutdown()
            if (closeError == null && failure != null) throw failure
        }

        // Do not expose the lifetime-owning stage: cancelling a whenComplete
        // dependent before its source settles suppresses that callback. A
        // disposable dependent lets callers cancel their wait while cleanup,
        // failure publication, and executor shutdown still run to completion.
        return lifetimeFuture.thenApply { completion -> completion }
    }

    private fun runWriterLoop() {
        try {
            while (true) {
                val record = queue.take()
                when (record) {
                    is SnapshotChunkRecord.Success -> handleSuccess(record)
                    is SnapshotChunkRecord.PreSerialized -> handlePreSerialized(record)
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
        runCatching {
            val stats = fileManager.appendSuccess(record)
            if (stats != null) {
                publishWhenUploaded(stats)
            }
        }.exceptionOrNull()?.let { failure ->
            if (failure is ChunkArtifactWriteException || failure is Error) throw failure
            log.warn("[Sink] invalid bodyBytes for key={}, treating as failure: {}", record.key, failure.message)
            appendRejectedRecord(record, "invalid body: ${failure.message}")
        }
    }

    /**
     * Handle a producer-serialized record. Mirrors [handleSuccess] but
     * skips the writer-side Jackson serialize. The producer already
     * appended a trailing newline to `record.bodyBytes`; gzip only writes
     * the bytes verbatim. See ADR-729.
     */
    private fun handlePreSerialized(record: SnapshotChunkRecord.PreSerialized) {
        runCatching {
            val stats = fileManager.appendPreSerialized(record)
            if (stats != null) {
                publishWhenUploaded(stats)
            }
        }.exceptionOrNull()?.let { failure ->
            if (failure is ChunkArtifactWriteException || failure is Error) throw failure
            log.warn(
                "[Sink] invalid pre-serialized body for key={}, treating as failure: {}",
                record.key,
                failure.message,
            )
            appendRejectedRecord(record, "invalid pre-serialized body: ${failure.message}")
        }
    }

    private fun appendRejectedRecord(record: SnapshotChunkRecord, errorMessage: String) {
        when (record) {
            is SnapshotChunkRecord.Success -> fileManager.appendFailure(
                SnapshotChunkRecord.Failure(
                    key = record.key,
                    endpoint = record.endpoint,
                    keyType = record.keyType,
                    httpStatus = record.httpStatus,
                    fetchedAt = record.fetchedAt,
                    errorMessage = errorMessage,
                ),
            )

            is SnapshotChunkRecord.PreSerialized -> fileManager.appendFailure(
                SnapshotChunkRecord.Failure(
                    key = record.key,
                    endpoint = record.endpoint,
                    keyType = record.keyType,
                    httpStatus = record.httpStatus,
                    fetchedAt = record.fetchedAt,
                    errorMessage = errorMessage,
                ),
            )

            is SnapshotChunkRecord.Failure,
            SnapshotChunkRecord.CloseSignal,
            -> error("only successful record variants can be rejected during chunk append")
        }
    }

    private fun ensureWriterExecutorTerminated() {
        if (!writerExecutor.isTerminated) writerExecutor.shutdownNow()
    }

    private fun handleRunFailure(failure: Throwable) {
        runFailureStep(failure) { fileManager.abortCurrentChunk(failure) }
        runFailureStep(failure) { fileManager.cleanupOnFailure() }
        runFailureStep(failure) {
            eventPublisher.publishRunFailed(
                fileManager.manifest(),
                endpoint,
                failure.message ?: "unknown",
            )
        }
    }

    private fun runFailureStep(primary: Throwable, action: () -> Unit) {
        runCatching(action)
            .exceptionOrNull()
            ?.takeIf { failure -> failure !== primary }
            ?.let(primary::addSuppressed)
    }

    private fun combineFailures(primary: Throwable?, secondary: Throwable?): Throwable? {
        if (primary == null) return secondary
        if (secondary != null && secondary !== primary) primary.addSuppressed(secondary)
        return primary
    }

    private fun unwrapCompletionFailure(failure: Throwable): Throwable = when (failure) {
        is java.util.concurrent.CompletionException,
        is java.util.concurrent.ExecutionException,
        -> failure.cause?.let(::unwrapCompletionFailure) ?: failure

        else -> failure
    }

    private fun logClosed(manifest: SnapshotChunkManifest) {
        log.info(
            "[Sink] closed: endpoint={}, chunks={}, records={}, failed={}",
            endpoint,
            manifest.chunks.size,
            manifest.totalRecords,
            manifest.totalFailed,
        )
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
        future.whenComplete { receipt, ex ->
            if (ex != null) {
                log.warn(
                    "[Sink] chunk upload failed, skipping chunk-ready publish: partIndex={} error={}",
                    stats.partIndex,
                    ex.message,
                )
                return@whenComplete
            }
            val completedReceipt = receipt ?: return@whenComplete
            eventPublisher.publishChunkReady(
                stats,
                completedReceipt,
                fileManager.manifest().runId,
                endpoint,
            )
        }
    }
}
