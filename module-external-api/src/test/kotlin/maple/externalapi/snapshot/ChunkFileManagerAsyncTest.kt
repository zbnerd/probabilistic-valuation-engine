package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import maple.pipeline.artifact.write.DefaultArtifactWriter
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Async contract tests for [ChunkFileManager.awaitAllUploadsAsync].
 *
 * Sub-PR 4 (audit reference: docs/05_Reports/2026-06-18-blocking-audit.md line 69)
 * keeps a CF-returning boundary so callers can chain via `thenCompose`
 * without holding a thread hostage on a 10-minute timeout.
 *
 * Two guarantees under test:
 *  - Empty in-flight list returns a CF that is already completed with `true`
 *    synchronously (no scheduling latency, no thread hop).
 *  - Non-empty in-flight list returns a CF that has NOT yet completed
 *    (i.e. the call site does not block waiting for uploads).
 */
class ChunkFileManagerAsyncTest {

    private val objectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `awaitAllUploadsAsync returns completed CF true when no in-flight uploads`() {
        val storage = mock<ConditionalObjectStorage>()

        val manager = ChunkFileManager(
            runId = "test",
            endpoint = "test",
            maxRecords = 100,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            clock = Clock.systemUTC(),
            objectStorage = storage,
            artifactWriter = DefaultArtifactWriter(storage, java.util.concurrent.Executor { command -> command.run() }),
        )

        val future: CompletableFuture<Boolean> = manager.awaitAllUploadsAsync()

        assertThat(future.isDone).isEqualTo(true)
        assertThat(future.resultNow()).isEqualTo(true)
        assertThat(manager.inFlightUploadCount()).isEqualTo(0)
    }

    @Test
    fun `awaitAllUploadsAsync returns CF immediately without blocking on uploads`() {
        val storage = mock<ConditionalObjectStorage>()
        val neverCompletes = CompletableFuture<PutResult>()
        whenever(storage.putFileAsync(any<String>(), any<Path>())).thenReturn(neverCompletes)
        val tempFilesBefore = artifactTempFiles()

        val manager = ChunkFileManager(
            runId = "test",
            endpoint = "test",
            maxRecords = 1,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            clock = Clock.systemUTC(),
            objectStorage = storage,
            artifactWriter = DefaultArtifactWriter(storage, java.util.concurrent.Executor { command -> command.run() }),
        )

        try {
            // Trigger one rotation: appendSuccess with maxRecords=1 forces rotation,
            // which registers the never-completing upload future into inFlightUploads.
            manager.appendSuccess(
                SnapshotChunkRecord.Success(
                    bodyBytes = objectMapper.writeValueAsBytes(mapOf("k" to "v")),
                    key = "k1",
                    endpoint = "test",
                    keyType = "TEST",
                    httpStatus = 200,
                    fetchedAt = Instant.parse("2026-06-18T00:00:00Z"),
                ),
            )
            assertThat(manager.inFlightUploadCount()).isEqualTo(1)

            // The CF must be returned synchronously (well under 100ms) and
            // must NOT be done yet — otherwise the call would block on uploads.
            val start = System.nanoTime()
            val future = manager.awaitAllUploadsAsync()
            val returnMs = (System.nanoTime() - start) / 1_000_000

            assertThat(returnMs)
                .withFailMessage("awaitAllUploadsAsync should return synchronously but took ${returnMs}ms")
                .isLessThan(100)
            assertThat(future.isDone).isFalse()
        } finally {
            neverCompletes.complete(PutResult("test", 0L, null))
            manager.closeCurrentChunk()
            await().atMost(Duration.ofSeconds(5)).until { artifactTempFiles() == tempFilesBefore }
        }
        assertThat(artifactTempFiles()).isEqualTo(tempFilesBefore)
    }

    private fun artifactTempFiles(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { path -> path.fileName.toString().startsWith("artifact-gzip-") }
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .toList()
            .toSet()
    }
}
