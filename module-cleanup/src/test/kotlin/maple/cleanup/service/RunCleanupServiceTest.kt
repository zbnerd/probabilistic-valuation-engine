package maple.cleanup.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import maple.common.cleanup.RunCleanupResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RunCleanupServiceTest {
    @Test
    fun `cleanup of empty basePath returns ZERO result`(@TempDir tmp: Path) {
        val service = RunCleanupService(basePath = tmp.toString(), properties = props(dryRun = false))
        val result = service.cleanupRuns()
        assertEquals(RunCleanupResult.ZERO, result)
    }

    @Test
    fun `cleanup deletes old runs and keeps recent ones`(@TempDir tmp: Path) {
        val runsDir = Files.createDirectory(tmp.resolve("runs"))
        // 5 recent (within 12h), 2 old (>48h). runId format: yyyyMMdd-HHmmss-nanos
        repeat(5) { i -> Files.createDirectory(runsDir.resolve(recentRunId(i))) }
        Files.createDirectory(runsDir.resolve("20260601-120000-000000000")) // ~6 days old
        Files.createDirectory(runsDir.resolve("20260530-120000-000000000")) // ~8 days old

        val service = RunCleanupService(basePath = tmp.toString(), properties = props(dryRun = false))
        val result = service.cleanupRuns()

        assertEquals(2, result.runsDeleted)
        assertEquals(5, Files.list(runsDir).use { it.count() })
    }

    private fun recentRunId(i: Int): String {
        // Generate a timestamp `i` minutes in the past
        val now = java.time.LocalDateTime.now()
        val past = now.minusMinutes(i.toLong())
        return String.format(
            "%04d%02d%02d-%02d%02d%02d-%09d",
            past.year,
            past.monthValue,
            past.dayOfMonth,
            past.hour,
            past.minute,
            past.second,
            i,
        )
    }

    private fun props(dryRun: Boolean) = maple.cleanup.config.CleanupProperties(dryRun = dryRun)
}
