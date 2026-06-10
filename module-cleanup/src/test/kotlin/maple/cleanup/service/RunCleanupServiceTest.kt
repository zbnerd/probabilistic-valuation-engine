package maple.cleanup.service

import java.time.Instant
import maple.cleanup.config.CleanupProperties
import maple.common.cleanup.RunCleanupResult
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RunCleanupServiceTest {
    @Test
    fun `cleanupRuns lists runs via listByPrefix and skips runs with _RUNNING marker`() {
        val storage = mock<ObjectStorage>()
        val now = Instant.parse("2026-06-10T00:00:00Z")
        // runId format: yyyyMMdd-HHmmss-{nanos} — timestamp is in the past relative to "now"
        whenever(storage.listByPrefix("runs/")).thenReturn(
            listOf(
                ObjectInfo("runs/20260608-100000-000000001/manifest.json", 100L, now),
                ObjectInfo("runs/20260608-100000-000000001/_RUNNING", 1L, now),
                ObjectInfo("runs/20260608-100000-000000002/manifest.json", 200L, now),
                ObjectInfo("runs/20260608-100000-000000003/manifest.json", 300L, now),
            ),
        )
        whenever(storage.exists("runs/20260608-100000-000000001/_RUNNING")).thenReturn(true)
        whenever(storage.exists("runs/20260608-100000-000000002/_RUNNING")).thenReturn(false)
        whenever(storage.exists("runs/20260608-100000-000000003/_RUNNING")).thenReturn(false)
        whenever(storage.calculatePrefixSize("runs/20260608-100000-000000002")).thenReturn(1_000L)
        whenever(storage.calculatePrefixSize("runs/20260608-100000-000000003")).thenReturn(2_000L)
        whenever(storage.deleteByPrefix("runs/20260608-100000-000000002")).thenReturn(1L)
        whenever(storage.deleteByPrefix("runs/20260608-100000-000000003")).thenReturn(1L)

        val service = RunCleanupService(
            properties = props(dryRun = false, keepRecent = 0, keepWithinHours = 0),
            objectStorage = storage,
        )
        service.cleanupPrefix("runs", now = now)

        verify(storage).listByPrefix("runs/")
        verify(storage, never()).deleteByPrefix("runs/20260608-100000-000000001")
        verify(storage).deleteByPrefix("runs/20260608-100000-000000002")
        verify(storage).deleteByPrefix("runs/20260608-100000-000000003")
    }

    @Test
    fun `cleanupRuns returns ZERO when no runs found`() {
        val storage = mock<ObjectStorage>()
        whenever(storage.listByPrefix("runs/")).thenReturn(emptyList())

        val service = RunCleanupService(
            properties = props(dryRun = false, keepRecent = 0, keepWithinHours = 0),
            objectStorage = storage,
        )
        val result = service.cleanupRuns()

        assertThat(result).isEqualTo(RunCleanupResult.ZERO)
        verify(storage, never()).deleteByPrefix(any<String>())
    }

    @Test
    fun `cleanupCalculatorRuns targets calculator_runs prefix`() {
        val storage = mock<ObjectStorage>()
        whenever(storage.listByPrefix("calculator/runs/")).thenReturn(emptyList())

        val service = RunCleanupService(
            properties = props(dryRun = false, keepRecent = 0, keepWithinHours = 0),
            objectStorage = storage,
        )
        service.cleanupCalculatorRuns()

        verify(storage).listByPrefix("calculator/runs/")
    }

    @Test
    fun `dryRun does not call deleteByPrefix`() {
        val storage = mock<ObjectStorage>()
        val now = Instant.parse("2026-06-10T00:00:00Z")
        whenever(storage.listByPrefix("runs/")).thenReturn(
            listOf(
                ObjectInfo("runs/20260608-100000-000000001/manifest.json", 100L, now),
            ),
        )
        whenever(storage.exists("runs/20260608-100000-000000001/_RUNNING")).thenReturn(false)
        whenever(storage.calculatePrefixSize("runs/20260608-100000-000000001")).thenReturn(1_000L)

        val service = RunCleanupService(
            properties = props(dryRun = true, keepRecent = 0, keepWithinHours = 0),
            objectStorage = storage,
        )
        service.cleanupPrefix("runs", now = now)

        verify(storage, never()).deleteByPrefix(any<String>())
    }

    @Test
    fun `unparseable runId is skipped without throwing`() {
        val storage = mock<ObjectStorage>()
        val now = Instant.parse("2026-06-10T00:00:00Z")
        whenever(storage.listByPrefix("runs/")).thenReturn(
            listOf(
                ObjectInfo("runs/not-a-timestamp/manifest.json", 100L, now),
            ),
        )
        whenever(storage.exists("runs/not-a-timestamp/_RUNNING")).thenReturn(false)
        whenever(storage.calculatePrefixSize("runs/not-a-timestamp")).thenReturn(1_000L)

        val service = RunCleanupService(
            properties = props(dryRun = false, keepRecent = 0, keepWithinHours = 0),
            objectStorage = storage,
        )
        val result = service.cleanupPrefix("runs", now = now)

        // not-a-timestamp is not a valid runId format → skipped
        verify(storage, never()).deleteByPrefix(any<String>())
        assertThat(result).isEqualTo(RunCleanupResult.ZERO)
    }

    private fun props(
        dryRun: Boolean,
        keepRecent: Int = 0,
        keepWithinHours: Long = 0,
    ) = CleanupProperties(
        dryRun = dryRun,
        runs = CleanupProperties.Runs(keepRecent = keepRecent, keepWithinHours = keepWithinHours),
        maxDeleteRunsPerCycle = 100,
        maxDeleteBytesPerCycle = 100_000_000L,
        maxRuntimeSeconds = 60,
    )
}
