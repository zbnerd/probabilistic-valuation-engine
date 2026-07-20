package maple.externalapi.snapshot

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Future
import maple.externalapi.metrics.OrphanCleanupMetrics
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.task.AsyncTaskExecutor
import kotlin.io.path.setLastModifiedTime

class OrphanTempFileCleanupHookTest {
    @TempDir
    lateinit var tmp: Path

    private val fixedNow = Instant.parse("2026-06-19T00:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var metrics: OrphanCleanupMetrics

    @BeforeEach
    fun setUpMetrics() {
        registry = SimpleMeterRegistry()
        metrics = OrphanCleanupMetrics(registry)
    }

    @Test
    fun `successful completion records summary and preserves active or unrelated files`() {
        val old = createOrphan("gzip-chunk-old.jsonl.gz.tmp", size = 1_024, ageHours = 2)
        val active = createOrphan("gzip-chunk-active.jsonl.gz.tmp", size = 512, ageHours = 0)
        val unrelated = createOrphan("urgent-chunk-old.jsonl.gz.tmp", size = 128, ageHours = 2)

        makeHook().run(mock())

        assertThat(Files.exists(old)).isFalse()
        assertThat(Files.exists(active)).isTrue()
        assertThat(Files.exists(unrelated)).isTrue()
        assertThat(outcomeCount("success")).isEqualTo(1.0)
        assertThat(counter("external_api_orphan_cleanup_scanned_total")).isEqualTo(2.0)
        assertThat(counter("external_api_orphan_cleanup_deleted_total")).isEqualTo(1.0)
        assertThat(counter("external_api_orphan_cleanup_bytes_freed_total")).isEqualTo(1_024.0)
    }

    @Test
    fun `submit failure is recorded without failing application startup`() {
        val submitFailure = IllegalStateException("submit failed")
        val rejectingExecutor = AsyncTaskExecutor { throw submitFailure }
        val hook = makeHook(asyncExecutor = rejectingExecutor)

        assertThatCode { hook.run(mock()) }.doesNotThrowAnyException()

        assertThat(outcomeCount("submit_failed")).isEqualTo(1.0)
        assertThat(outcomeCount("failed")).isEqualTo(0.0)
    }

    @Test
    fun `timeout records outcome and interrupts the submitted future without failing startup`() {
        val asyncExecutor = mock<AsyncTaskExecutor>()
        val submitted = mock<Future<*>>()
        whenever(asyncExecutor.submit(any<Runnable>())).thenReturn(submitted)
        val hook = makeHook(asyncExecutor = asyncExecutor, timeout = Duration.ZERO)

        assertThatCode { hook.run(mock()) }.doesNotThrowAnyException()

        await().untilAsserted {
            assertThat(outcomeCount("timeout")).isEqualTo(1.0)
            verify(submitted).cancel(true)
        }
    }

    @Test
    fun `scan failure records failed and leaves filesystem for the next boot`() {
        val notDirectory = tmp.resolve("not-a-directory")
        Files.writeString(notDirectory, "keep")
        val hook = makeHook(scanDir = notDirectory)

        assertThatCode { hook.run(mock()) }.doesNotThrowAnyException()

        assertThat(outcomeCount("failed")).isEqualTo(1.0)
        assertThat(Files.readString(notDirectory)).isEqualTo("keep")
    }

    @Test
    fun `delete failure records failed and leaves the orphan for retry`() {
        val orphanDirectory = tmp.resolve("gzip-chunk-directory.jsonl.gz.tmp")
        Files.createDirectory(orphanDirectory)
        Files.writeString(orphanDirectory.resolve("held"), "keep")
        orphanDirectory.setLastModifiedTime(FileTime.from(fixedNow.minus(Duration.ofHours(2))))

        makeHook().run(mock())

        assertThat(outcomeCount("failed")).isEqualTo(1.0)
        assertThat(counter("external_api_orphan_cleanup_failed_total")).isEqualTo(1.0)
        assertThat(Files.exists(orphanDirectory)).isTrue()
    }

    private fun makeHook(
        scanDir: Path = tmp,
        asyncExecutor: AsyncTaskExecutor = AsyncTaskExecutor { task -> task.run() },
        timeout: Duration = Duration.ofSeconds(30),
    ): OrphanTempFileCleanupHook = OrphanTempFileCleanupHook(
        asyncExecutor = asyncExecutor,
        metrics = metrics,
        clock = clock,
        scanDir = scanDir,
        timeout = timeout,
    )

    private fun createOrphan(
        name: String,
        size: Int,
        ageHours: Long,
    ): Path = tmp.resolve(name).also { file ->
        Files.write(file, ByteArray(size))
        file.setLastModifiedTime(FileTime.from(fixedNow.minus(Duration.ofHours(ageHours))))
    }

    private fun outcomeCount(result: String): Double =
        registry.find("external_api_orphan_cleanup_total")
            .tag("result", result)
            .counter()
            ?.count()
            ?: 0.0

    private fun counter(name: String): Double = registry.find(name).counter()?.count() ?: 0.0
}
