package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Async contract tests for [ChunkFileManager.awaitAllUploadsAsync].
 *
 * Sub-PR 4 (audit reference: docs/05_Reports/2026-06-18-blocking-audit.md line 69)
 * replaces the blocking `.get(600_000L, TimeUnit.MILLISECONDS)` in
 * [ChunkFileManager.awaitAllUploads] with a CF-returning variant so callers can
 * chain via `thenCompose` without holding a thread hostage on a 10-minute timeout.
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
        val storage = mock<ObjectStorage>()

        val manager = ChunkFileManager(
            runKey = "runs/test/empty",
            endpoint = "test",
            maxRecords = 100,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            clock = Clock.systemUTC(),
            objectStorage = storage,
        )

        val future: CompletableFuture<Boolean> = manager.awaitAllUploadsAsync()

        assertThat(future.isDone).isEqualTo(true)
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(true)
        assertThat(manager.inFlightUploadCount()).isEqualTo(0)
    }

    @Test
    fun `awaitAllUploadsAsync returns CF immediately without blocking on uploads`() {
        val storage = mock<ObjectStorage>()
        val neverCompletes = CompletableFuture<PutResult>()
        whenever(storage.putFileAsync(any<String>(), any<Path>())).thenReturn(neverCompletes)

        val manager = ChunkFileManager(
            runKey = "runs/test/inflight",
            endpoint = "test",
            maxRecords = 1,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            clock = Clock.systemUTC(),
            objectStorage = storage,
        )

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
        val isDone: Boolean = future.isDone
        assertThat(isDone).isFalse()
    }
}
