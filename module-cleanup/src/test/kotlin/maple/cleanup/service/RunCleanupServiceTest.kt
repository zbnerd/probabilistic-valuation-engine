package maple.cleanup.service

import maple.common.cleanup.RunCleanupResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class RunCleanupServiceTest {
    @Test
    fun `cleanup of empty basePath returns ZERO result`(@TempDir tmp: Path) {
        val service = RunCleanupService(basePath = tmp.toString(), properties = props())
        val result = service.cleanupRuns()
        assertEquals(RunCleanupResult.ZERO, result)
    }

    private fun props() = maple.cleanup.config.CleanupProperties()
}
