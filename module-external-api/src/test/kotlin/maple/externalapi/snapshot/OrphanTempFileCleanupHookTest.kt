package maple.externalapi.snapshot

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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

    private lateinit var logAppender: ListAppender<ILoggingEvent>
    private var originalLevel: Level? = null

    @BeforeEach
    fun attachAppender() {
        val logger = LoggerFactory.getLogger(OrphanTempFileCleanupHook::class.java) as Logger
        originalLevel = logger.level
        logger.level = Level.INFO
        logAppender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logAppender)
    }

    @AfterEach
    fun detachAppender() {
        val logger = LoggerFactory.getLogger(OrphanTempFileCleanupHook::class.java) as Logger
        logger.detachAppender(logAppender)
        logger.level = originalLevel
    }

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

    @Test
    fun `preserves files newer than 1 hour (active writer)`() {
        val file = createOrphan("gzip-chunk-uuid2-part-000002-.jsonl.gz.tmp", ageHours = 0)
        makeHook().run(mock())
        assertThat(Files.exists(file)).isTrue
    }

    @Test
    fun `preserves file exactly 1 hour old (cutoff boundary)`() {
        val file = createOrphan("gzip-chunk-uuid3-part-000003-.jsonl.gz.tmp", ageHours = 1)
        makeHook().run(mock())
        assertThat(Files.exists(file)).isTrue
    }

    @Test
    fun `ignores non-matching filenames`() {
        val unrelated = createOrphan("urgent-chunk-uuid-part-000001-.jsonl.gz.tmp", ageHours = 24)
        val plainTxt = tmp.resolve("notes.txt")
        Files.write(plainTxt, ByteArray(10))
        plainTxt.setLastModifiedTime(FileTime.from(fixedNow.minusSeconds(24 * 3600)))
        val olderPrefix = tmp.resolve("gzip-archive.jsonl.gz") // not tmp suffix
        Files.write(olderPrefix, ByteArray(10))
        olderPrefix.setLastModifiedTime(FileTime.from(fixedNow.minusSeconds(24 * 3600)))

        makeHook().run(mock())

        assertThat(Files.exists(unrelated)).isTrue
        assertThat(Files.exists(plainTxt)).isTrue
        assertThat(Files.exists(olderPrefix)).isTrue
    }

    @Test
    fun `continues after individual delete failure`() {
        val good = createOrphan("gzip-chunk-uuid4-part-000004-.jsonl.gz.tmp", ageHours = 2)
        val held = createOrphan("gzip-chunk-uuid5-part-000005-.jsonl.gz.tmp", ageHours = 2)
        // Make the file un-deletable on POSIX. Test is no-op on Windows.
        held.toFile().setReadable(false)
        held.toFile().setWritable(false)

        makeHook().run(mock())

        assertThat(Files.exists(good)).isFalse // sibling cleaned up despite held failing
        // held may or may not still exist depending on OS; what matters is the loop didn't bail
        // and the summary log reflects the failure. Cleanup perm for next test:
        held.toFile().setReadable(true)
        held.toFile().setWritable(true)
    }

    @Test
    fun `logs scanned deleted bytes_freed at INFO`() {
        createOrphan("gzip-chunk-uuid6-part-000006-.jsonl.gz.tmp", size = 1024, ageHours = 2)
        createOrphan("gzip-chunk-uuid7-part-000007-.jsonl.gz.tmp", size = 512, ageHours = 0) // skipped (active)

        makeHook().run(mock())

        val summary = logAppender.list
            .firstOrNull { it.formattedMessage.startsWith("[OrphanTempFileCleanup] scanned=") }
        assertThat(summary).isNotNull
        assertThat(summary!!.level).isEqualTo(Level.INFO)
        val msg = summary.formattedMessage
        assertThat(msg).contains("scanned=2")
        assertThat(msg).contains("deleted=1")
        assertThat(msg).contains("bytes_freed=1024")
        assertThat(msg).contains("failed=0")
    }

    @Test
    fun `runWithDeadline logs WARN and cancels when timeout fires`() {
        // Executor that never invokes the task — future stays pending.
        // timeoutSeconds = 0 → future.get(0, SECONDS) throws TimeoutException immediately.
        // runWithDeadline must catch it, log WARN, and cancel the future.
        val neverRunsExecutor = Executor { /* drop the command */ }
        val hook = makeHook(asyncExecutor = neverRunsExecutor, timeoutSeconds = 0)

        hook.runWithDeadline()

        val warn = logAppender.list
            .firstOrNull { it.formattedMessage.contains("cleanup exceeded 0s") }
        assertThat(warn).isNotNull
        assertThat(warn!!.level).isEqualTo(Level.WARN)
    }

    @Test
    fun `runWithDeadline logs ERROR when submit fails`() {
        // Executor that throws on submit — runAsync never creates the future; submit-fail
        // path runs and logs the consolidated ERROR message.
        val throwingExecutor = Executor { throw RuntimeException("simulated submit failure") }
        val hook = makeHook(asyncExecutor = throwingExecutor)

        hook.runWithDeadline()

        val err = logAppender.list
            .firstOrNull { it.formattedMessage.contains("cleanup submit failed") }
        assertThat(err).isNotNull
        assertThat(err!!.level).isEqualTo(Level.ERROR)
    }
}