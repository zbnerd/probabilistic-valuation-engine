package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import maple.pipeline.artifact.lifecycle.RunLifecycle
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.write.ArtifactReceipt
import maple.pipeline.artifact.write.DefaultArtifactWriter
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.never
import org.mockito.kotlin.whenever

class ChunkedSnapshotSinkTest {

    private val objectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())

    @Test
    fun `submit throws after writer thread dies from Error, not Exception`() {
        // Reproduces the production symptom: writer thread dies from
        // an Error (e.g. OOMError under heap pressure) and a subsequent
        // submit() call observes writerFuture.isDone = true before the
        // runWriterLoop catch can set writerError. Before the fix the
        // submit throws a vague "writer thread is not alive" message
        // and the original OOMError is lost. After the fix the writer
        // catch clause widens to Throwable so the original error is
        // propagated as "sink closed due to writer error: ...".

        val appendInvoked = CountDownLatch(1)
        val capturedBodyBytes = AtomicReference<ByteArray>()

        val fileManager = mock<ChunkFileManager>()
        whenever(fileManager.appendSuccess(any())).thenAnswer { invocation ->
            val record = invocation.getArgument<SnapshotChunkRecord.Success>(0)
            capturedBodyBytes.set(record.bodyBytes)
            appendInvoked.countDown()
            // Throw an Error (NOT Exception) — simulates heap pressure
            // or similar unrecoverable condition. The old catch (ex: Exception)
            // would NOT catch this and the thread would die silently.
            throw OutOfMemoryError("simulated writer OOM")
        }

        val eventPublisher = mock<SnapshotSinkEventPublisher>()

        val sink = ChunkedSnapshotSink(
            endpoint = "item-equipment",
            queueCapacity = 100,
            fileManager = fileManager,
            eventPublisher = eventPublisher,
            runLifecycle = mock(),
        )

        // First submit succeeds (queue.offer, writer picks it up).
        val body = objectMapper.writeValueAsBytes(
            mapOf("userIgn" to "user-1", "ocid" to "ocid-1"),
        )
        sink.submit(
            SnapshotChunkRecord.Success(
                key = "user-1",
                endpoint = "item-equipment",
                keyType = "OCID",
                httpStatus = 200,
                fetchedAt = Instant.parse("2026-06-11T00:00:00Z"),
                bodyBytes = body,
            ),
        )

        // Wait for writer to start processing the first record (it will then
        // throw OutOfMemoryError, which under the bug kills the thread
        // silently without setting writerError or accepting=false).
        assertTrue(
            appendInvoked.await(2, TimeUnit.SECONDS),
            "writer thread should have invoked fileManager.appendSuccess",
        )

        // Wait for the writer Future to be done (thread exited).
        val writerField = ChunkedSnapshotSink::class.java.getDeclaredField("writerFuture")
            .apply { isAccessible = true }
        val writerFuture = writerField.get(sink) as java.util.concurrent.Future<*>
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline && !writerFuture.isDone) Thread.sleep(20)
        assertTrue(writerFuture.isDone, "writer thread should have terminated")

        // Second submit: under the BUG this throws
        //   IllegalStateException("sink writer thread is not alive")
        // because writerError stayed null (the catch never saw the OOM)
        // and accepting stayed true. After the FIX it should throw
        //   IllegalStateException("sink closed due to writer error: simulated writer OOM")
        // because the writer's Throwable catch records the error and flips
        // accepting to false.
        val ex = assertThrows(IllegalStateException::class.java) {
            sink.submit(
                SnapshotChunkRecord.Success(
                    key = "user-2",
                    endpoint = "item-equipment",
                    keyType = "OCID",
                    httpStatus = 200,
                    fetchedAt = Instant.parse("2026-06-11T00:00:00Z"),
                    bodyBytes = objectMapper.writeValueAsBytes(
                        mapOf("userIgn" to "user-2", "ocid" to "ocid-2"),
                    ),
                ),
            )
        }
        val message = ex.message ?: ""
        // The original OOM must surface in the message — not just a vague
        // "writer thread is not alive". This is what the fix enables.
        assertTrue(
            message.contains("writer thread is not alive").not() &&
                (message.contains("simulated writer OOM") || message.contains("sink closed due to writer error")),
            "expected submit() to surface the underlying OOMError, got: $message",
        )
    }

    @Test
    fun `async close aborts active chunk after writer failure and removes temp artifact`() {
        val fixture = failureFixture("async")

        try {
            fixture.sink.submitPreSerialized(successRecord("accepted-async"))
            awaitActiveChunk(fixture)
            fixture.sink.submit(failureRecord("fatal-async"))
            val completion = AtomicReference<Throwable?>()

            fixture.sink.closeAsync().whenComplete { _, failure -> completion.set(failure) }

            await().atMost(Duration.ofSeconds(5)).until { completion.get() != null }
            assertThat(completion.get()).hasRootCauseMessage("simulated async writer failure")
            await().atMost(Duration.ofSeconds(2)).until { artifactTempFiles() == fixture.tempFilesBefore }
            assertThat(artifactTempFiles()).isEqualTo(fixture.tempFilesBefore)
        } finally {
            runCatching { fixture.fileManager.closeCurrentChunk() }
            await().atMost(Duration.ofSeconds(5)).until { artifactTempFiles() == fixture.tempFilesBefore }
        }
    }

    @Test
    fun `cancelling returned async close future does not suppress later failure cleanup`() {
        val uploadsCompleted = CompletableFuture<Boolean>()
        val fileManager = mock<ChunkFileManager>()
        val eventPublisher = mock<SnapshotSinkEventPublisher>()
        val manifest = SnapshotChunkManifest(
            runId = "cancelled-close",
            endpoint = "ranking-overall",
            startedAt = Instant.EPOCH,
        )
        whenever(fileManager.manifest()).thenReturn(manifest)
        whenever(fileManager.awaitAllUploadsAsync(any())).thenReturn(uploadsCompleted)
        whenever(eventPublisher.publishRunFailed(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null))
        val sink = ChunkedSnapshotSink(
            endpoint = "ranking-overall",
            queueCapacity = 10,
            fileManager = fileManager,
            eventPublisher = eventPublisher,
            runLifecycle = mock(),
        )

        try {
            val callerFuture = sink.closeAsync()
            await().atMost(Duration.ofSeconds(2)).untilAsserted {
                verify(fileManager).awaitAllUploadsAsync(any())
            }

            assertThat(callerFuture.cancel(true)).isTrue()
            uploadsCompleted.complete(false)

            await().atMost(Duration.ofSeconds(2)).untilAsserted {
                verify(fileManager).abortCurrentChunk(any())
                verify(fileManager).cleanupIncompleteArtifacts()
                verify(eventPublisher).publishRunFailed(
                    eq(manifest),
                    eq("ranking-overall"),
                    eq("chunk uploads did not complete in time (in-flight=0)"),
                )
            }
        } finally {
            val executorField = ChunkedSnapshotSink::class.java.getDeclaredField("closeAsyncExecutor")
                .apply { isAccessible = true }
            (executorField.get(sink) as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }

    @Test
    fun `close tracks chunk publication before run completion and marker deletion`() {
        val (storage, objects) = lifecycleStorage()
        val lifecycle = RunLifecycle(storage, java.util.concurrent.Executor(Runnable::run))
        awaitFuture(lifecycle.startEndpoint("tracked-run", "item-equipment"))
        val fileManager = closingFileManager("tracked-run")
        val eventPublisher = mock<SnapshotSinkEventPublisher>()
        val chunkPublished = CompletableFuture<Void>()
        whenever(eventPublisher.publishChunkReady(any(), any(), any(), any())).thenReturn(chunkPublished)
        whenever(eventPublisher.publishRunCompleted(any(), any())).thenReturn(CompletableFuture.completedFuture(null))
        whenever(eventPublisher.publishRunFailed(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null))
        val sink = ChunkedSnapshotSink(
            endpoint = "item-equipment",
            queueCapacity = 10,
            fileManager = fileManager,
            eventPublisher = eventPublisher,
            runLifecycle = lifecycle,
        )

        val closeFuture = sink.closeAsync()
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            verify(eventPublisher).publishChunkReady(any(), any(), any(), any())
        }

        assertThat(closeFuture.isDone).isFalse()
        assertThat(objects).containsKeys(
            SourceArtifactLayout.endpointRunning("tracked-run", "item-equipment").value,
            SourceArtifactLayout.endpointSuccess("tracked-run", "item-equipment").value,
        )
        verify(eventPublisher, never()).publishRunCompleted(any(), any())

        chunkPublished.complete(null)
        val outcome = awaitFuture(closeFuture)
        assertThat(outcome.failure).isNull()
        verify(eventPublisher).publishRunCompleted(any(), any())
        assertThat(objects).doesNotContainKey(
            SourceArtifactLayout.endpointRunning("tracked-run", "item-equipment").value,
        )
    }

    @Test
    fun `required chunk publication failure retains success and running markers`() {
        val (storage, objects) = lifecycleStorage()
        val lifecycle = RunLifecycle(storage, java.util.concurrent.Executor(Runnable::run))
        awaitFuture(lifecycle.startEndpoint("failed-publish", "item-equipment"))
        val fileManager = closingFileManager("failed-publish")
        val eventPublisher = mock<SnapshotSinkEventPublisher>()
        whenever(eventPublisher.publishChunkReady(any(), any(), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(IllegalStateException("chunk broker failure")))
        whenever(eventPublisher.publishRunFailed(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null))
        val sink = ChunkedSnapshotSink(
            endpoint = "item-equipment",
            queueCapacity = 10,
            fileManager = fileManager,
            eventPublisher = eventPublisher,
            runLifecycle = lifecycle,
        )

        val outcome = awaitFuture(sink.closeAsync())

        assertThat(outcome.failure).hasRootCauseMessage("chunk broker failure")
        assertThat(objects).containsKeys(
            SourceArtifactLayout.endpointRunning("failed-publish", "item-equipment").value,
            SourceArtifactLayout.endpointSuccess("failed-publish", "item-equipment").value,
        )
        verify(fileManager, never()).cleanupIncompleteArtifacts()
        verify(eventPublisher).publishRunFailed(any(), any(), any())
    }

    @Test
    fun `run failed publication is awaited and suppressed under original failure`() {
        val fileManager = mock<ChunkFileManager>()
        val manifest = SnapshotChunkManifest(
            runId = "source-failure",
            endpoint = "item-equipment",
            startedAt = Instant.EPOCH,
        )
        whenever(fileManager.manifest()).thenReturn(manifest)
        whenever(fileManager.awaitAllUploadsAsync(any())).thenReturn(CompletableFuture.completedFuture(false))
        val eventPublisher = mock<SnapshotSinkEventPublisher>()
        val failedPublication = CompletableFuture<Void>()
        whenever(eventPublisher.publishRunFailed(any(), any(), any())).thenReturn(failedPublication)
        val sink = ChunkedSnapshotSink(
            endpoint = "item-equipment",
            queueCapacity = 10,
            fileManager = fileManager,
            eventPublisher = eventPublisher,
            runLifecycle = mock(),
        )

        val closeFuture = sink.closeAsync()
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            verify(eventPublisher).publishRunFailed(any(), any(), any())
        }
        assertThat(closeFuture.isDone).isFalse()

        failedPublication.completeExceptionally(IllegalStateException("run-failed broker failure"))
        val outcome = awaitFuture(closeFuture)
        val original = unwrap(requireNotNull(outcome.failure))
        assertThat(original).hasMessage("chunk uploads did not complete in time (in-flight=0)")
        assertThat(original.suppressed.map { failure -> failure.message })
            .containsExactly("run-failed broker failure")
    }

    private fun failureFixture(label: String): FailureFixture {
        val storage = mock<ConditionalObjectStorage>()
        whenever(storage.get(any())).thenReturn(ByteArray(0))
        whenever(storage.put(any(), any())).thenThrow(IllegalStateException("simulated $label writer failure"))
        whenever(storage.putFileAsync(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val path = invocation.getArgument<Path>(1)
            CompletableFuture.completedFuture(PutResult(key, Files.size(path), null))
        }
        val fileManager = ChunkFileManager(
            runId = "failure-$label",
            endpoint = "ranking-overall",
            maxRecords = 100,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            clock = Clock.systemUTC(),
            objectStorage = storage,
            artifactWriter = DefaultArtifactWriter(storage, java.util.concurrent.Executor(Runnable::run)),
        )
        val eventPublisher = mock<SnapshotSinkEventPublisher>()
        whenever(eventPublisher.publishRunFailed(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null))
        return FailureFixture(
            sink = ChunkedSnapshotSink(
                endpoint = "ranking-overall",
                queueCapacity = 10,
                fileManager = fileManager,
                eventPublisher = eventPublisher,
                runLifecycle = RunLifecycle(storage, java.util.concurrent.Executor(Runnable::run)),
            ),
            fileManager = fileManager,
            tempFilesBefore = artifactTempFiles(),
        )
    }

    private fun closingFileManager(runId: String): ChunkFileManager {
        val fileManager = mock<ChunkFileManager>()
        val receipt = ArtifactReceipt(
            key = ArtifactKey.require("runs/$runId/item-equipment/chunks/part-000001.jsonl.gz"),
            compressedBytes = 10,
            uncompressedBytes = 20,
            contentSha256 = "sha256",
            backendTag = null,
        )
        val stats = ChunkStats(
            partIndex = 1,
            recordCount = 1,
            uncompressedBytes = 20,
            startedAt = Instant.parse("2026-07-19T12:00:00Z"),
            finishedAt = Instant.parse("2026-07-19T12:01:00Z"),
            uploadFuture = CompletableFuture.completedFuture(receipt),
        )
        val manifest = SnapshotChunkManifest(
            runId = runId,
            endpoint = "item-equipment",
            startedAt = Instant.parse("2026-07-19T12:00:00Z"),
            finishedAt = Instant.parse("2026-07-19T12:02:00Z"),
            totalRecords = 1,
        )
        whenever(fileManager.closeCurrentChunk()).thenReturn(stats)
        whenever(fileManager.awaitAllUploadsAsync(any())).thenReturn(CompletableFuture.completedFuture(true))
        whenever(fileManager.finalizeManifestBytes()).thenReturn(objectMapper.writeValueAsBytes(manifest))
        whenever(fileManager.manifest()).thenReturn(manifest)
        return fileManager
    }

    private fun lifecycleStorage(): Pair<ConditionalObjectStorage, ConcurrentHashMap<String, ByteArray>> {
        val storage = mock<ConditionalObjectStorage>()
        val objects = ConcurrentHashMap<String, ByteArray>()
        whenever(storage.put(any(), any<ByteArray>())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val bytes = invocation.getArgument<ByteArray>(1)
            objects[key] = bytes.copyOf()
            PutResult(key, bytes.size.toLong(), null)
        }
        whenever(storage.delete(any())).thenAnswer { invocation ->
            objects.remove(invocation.getArgument<String>(0))
            Unit
        }
        return storage to objects
    }

    private fun <T> awaitFuture(future: CompletableFuture<T>): FutureOutcome<T> {
        val captured = AtomicReference<FutureOutcome<T>>()
        future.whenComplete { value, failure -> captured.set(FutureOutcome(value, failure)) }
        await().atMost(Duration.ofSeconds(5)).until { captured.get() != null }
        return requireNotNull(captured.get())
    }

    private fun unwrap(failure: Throwable): Throwable = when (failure) {
        is java.util.concurrent.CompletionException,
        is java.util.concurrent.ExecutionException,
        -> failure.cause?.let(::unwrap) ?: failure

        else -> failure
    }

    private fun awaitActiveChunk(fixture: FailureFixture) {
        await().atMost(Duration.ofSeconds(5)).until {
            fixture.fileManager.manifest().totalRecords == 1 &&
                (artifactTempFiles() - fixture.tempFilesBefore).isNotEmpty()
        }
    }

    private fun successRecord(key: String): SnapshotChunkRecord.PreSerialized = SnapshotChunkRecord.PreSerialized(
        key = key,
        endpoint = "ranking-overall",
        keyType = "DATE_PAGE",
        httpStatus = 200,
        fetchedAt = Instant.EPOCH,
        bodyBytes = "{\"key\":\"$key\"}\n".toByteArray(),
    )

    private fun failureRecord(key: String): SnapshotChunkRecord.Failure = SnapshotChunkRecord.Failure(
        key = key,
        endpoint = "ranking-overall",
        keyType = "DATE_PAGE",
        httpStatus = 500,
        fetchedAt = Instant.EPOCH,
        errorMessage = "fatal",
    )

    private fun artifactTempFiles(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { path -> path.fileName.toString().startsWith("artifact-gzip-") }
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .toList()
            .toSet()
    }

    private data class FailureFixture(
        val sink: ChunkedSnapshotSink,
        val fileManager: ChunkFileManager,
        val tempFilesBefore: Set<Path>,
    )

    private data class FutureOutcome<T>(val value: T?, val failure: Throwable?)
}
