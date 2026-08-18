package maple.externalapi.snapshot

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import maple.pipeline.artifact.lifecycle.RunLifecycle
import maple.pipeline.artifact.lifecycle.RunState
import org.slf4j.LoggerFactory

class ChunkedSnapshotSink(
    private val endpoint: String,
    private val queueCapacity: Int,
    private val fileManager: ChunkFileManager,
    private val eventPublisher: SnapshotSinkEventPublisher,
    private val runLifecycle: RunLifecycle,
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread.ofPlatform().name("snapshot-writer-$endpoint").unstarted(runnable)
    },
) {
    private val log = LoggerFactory.getLogger(ChunkedSnapshotSink::class.java)

    private val queue = ArrayBlockingQueue<SnapshotChunkRecord>(queueCapacity)
    private val accepting = AtomicBoolean(true)
    private val writerError = AtomicReference<Throwable?>(null)
    private val lifecycleStarted = AtomicBoolean(false)
    private val requiredChunkPublishes: MutableList<CompletableFuture<Void>> = mutableListOf()

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

        val closePipeline = writerDoneFuture.thenCompose { finalizeAfterWriter() }
        val terminalPipeline = closePipeline
            .handle { _, closeError ->
                if (closeError == null) {
                    CompletableFuture.completedFuture<Void>(null)
                } else {
                    handleRunFailure(unwrapCompletionFailure(closeError))
                }
            }
            .thenCompose { completion -> completion }
        val lifetimeFuture = terminalPipeline.whenComplete { _, _ ->
            ensureWriterExecutorTerminated()
            closeAsyncExecutor.shutdown()
        }

        // Do not expose the lifetime-owning stage: cancelling a whenComplete
        // dependent before its source settles suppresses that callback. A
        // disposable dependent lets callers cancel their wait while cleanup,
        // failure publication, and executor shutdown still run to completion.
        return lifetimeFuture.thenApply { completion -> completion }
    }

    private fun finalizeAfterWriter(): CompletableFuture<Void> {
        val writerFailure = writerError.get()
        if (writerFailure != null) {
            return CompletableFuture.failedFuture(
                RuntimeException("writer thread failed: ${writerFailure.message}", writerFailure),
            )
        }

        fileManager.closeCurrentChunk()?.let(::publishWhenUploaded)
        return fileManager.awaitAllUploadsAsync(ChunkFileManager.DEFAULT_AWAIT_TIMEOUT_MS)
            .thenCompose { allUploaded ->
                if (!allUploaded) {
                    val message = "chunk uploads did not complete in time " +
                        "(in-flight=${fileManager.inFlightUploadCount()})"
                    return@thenCompose CompletableFuture.failedFuture(RuntimeException(message))
                }

                val manifestBytes = fileManager.finalizeManifestBytes()
                val manifest = fileManager.manifest()
                lifecycleStarted.set(true)
                runCatching {
                    runLifecycle.finalizeEndpoint(
                        runId = manifest.runId,
                        endpoint = endpoint,
                        manifestBytes = manifestBytes,
                        requiredPublish = { requiredPublications(manifest) },
                    )
                }.getOrElse { failure -> CompletableFuture.failedFuture(failure) }
                    .thenAccept { state -> logClosed(manifest, state) }
            }
    }

    private fun requiredPublications(manifest: SnapshotChunkManifest): CompletableFuture<Void> {
        val chunksPublished = CompletableFuture.allOf(*requiredChunkPublishes.toTypedArray())
        return chunksPublished.thenCompose { eventPublisher.publishRunCompleted(manifest, endpoint) }
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

    private fun handleRunFailure(failure: Throwable): CompletableFuture<Void> {
        val prepareFailure = CompletableFuture.runAsync(
            {
                runFailureStep(failure) { fileManager.abortCurrentChunk(failure) }
                if (!lifecycleStarted.get()) {
                    runFailureStep(failure) { fileManager.cleanupIncompleteArtifacts() }
                }
            },
            closeAsyncExecutor,
        )
        return prepareFailure.thenCompose { publishRunFailedPreserving(failure) }
    }

    private fun publishRunFailedPreserving(originalFailure: Throwable): CompletableFuture<Void> {
        val publication = runCatching {
            eventPublisher.publishRunFailed(
                fileManager.manifest(),
                endpoint,
                originalFailure.message ?: "unknown",
            )
        }.getOrElse { publicationFailure -> CompletableFuture.failedFuture(publicationFailure) }
        return publication.handle<Void> { _, publicationFailure ->
            publicationFailure
                ?.let(::unwrapCompletionFailure)
                ?.takeIf { failure -> failure !== originalFailure }
                ?.let(originalFailure::addSuppressed)
            throw originalFailure
        }
    }

    private fun runFailureStep(primary: Throwable, action: () -> Unit) {
        runCatching(action)
            .exceptionOrNull()
            ?.takeIf { failure -> failure !== primary }
            ?.let(primary::addSuppressed)
    }

    private fun unwrapCompletionFailure(failure: Throwable): Throwable = when (failure) {
        is java.util.concurrent.CompletionException,
        is java.util.concurrent.ExecutionException,
        -> failure.cause?.let(::unwrapCompletionFailure) ?: failure

        else -> failure
    }

    private fun logClosed(manifest: SnapshotChunkManifest, state: RunState) {
        log.info(
            "[Sink] closed: endpoint={}, chunks={}, records={}, failed={}, state={}",
            endpoint,
            manifest.chunks.size,
            manifest.totalRecords,
            manifest.totalFailed,
            state,
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
        val requiredPublish = stats.uploadFuture.thenCompose { receipt ->
            eventPublisher.publishChunkReady(
                stats,
                receipt,
                fileManager.manifest().runId,
                endpoint,
            )
        }
        requiredChunkPublishes.add(requiredPublish)
    }
}
