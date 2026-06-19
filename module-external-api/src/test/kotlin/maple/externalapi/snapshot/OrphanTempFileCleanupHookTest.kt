package maple.externalapi.snapshot

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import kotlin.io.path.setLastModifiedTime

class OrphanTempFileCleanupHookTest {

    @TempDir
    lateinit var tmp: Path

    private val fixedNow: Instant = Instant.parse("2026-06-19T00:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    // Stub LogicExecutor that invokes the Runnable passed to executeVoidJava synchronously.
    // We use executeVoidJava (Runnable-typed) instead of executeVoid (ThrowingRunnable) so the
    // mock signature and the production signature both round-trip via the same Runnable type.
    private val executor: LogicExecutor = mock<LogicExecutor>().also { m ->
        org.mockito.Mockito.`when`(m.executeVoidJava(any<Runnable>(), any<TaskContext>()))
            .thenAnswer { invocation ->
                (invocation.arguments[0] as Runnable).run()
                null
            }
    }

    // Default async executor: runs submitted Runnables synchronously on the caller thread.
    // CompletableFuture.runAsync uses this to start cleanup; the future then completes
    // synchronously. runWithDeadline's future.get(timeout) returns immediately.
    private val syncAsyncExecutor: Executor = Executor { it.run() }

    private fun makeHook(
        clock: Clock = this.clock,
        scanDir: Path = tmp,
        executor: LogicExecutor = this.executor,
        asyncExecutor: Executor = syncAsyncExecutor,
        timeoutSeconds: Long = 30,
    ): OrphanTempFileCleanupHook =
        OrphanTempFileCleanupHook(executor, asyncExecutor, clock, scanDir, timeoutSeconds)

    private fun createOrphan(
        name: String,
        size: Int = 10,
        ageHours: Long = 0,
    ): Path {
        val file = tmp.resolve(name)
        Files.write(file, ByteArray(size))
        file.setLastModifiedTime(FileTime.from(fixedNow.minusSeconds(ageHours * 3600)))
        return file
    }

    @Test
    fun `deletes files older than 1 hour`() {
        val file = createOrphan("gzip-chunk-uuid1-part-000001-.jsonl.gz.tmp", ageHours = 2)
        makeHook().run(mock())
        assertThat(Files.exists(file)).isFalse
    }
}