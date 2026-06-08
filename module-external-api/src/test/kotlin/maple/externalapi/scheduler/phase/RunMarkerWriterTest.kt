package maple.externalapi.scheduler.phase

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RunMarkerWriterTest {
    @Test
    fun `writeRunningMarker creates dir and writes clock instant`(@TempDir tempDir: Path) {
        val fixed = Clock.fixed(Instant.parse("2026-06-06T12:00:00Z"), ZoneId.of("UTC"))
        val writer = RunMarkerWriter(fixed)
        val runDir = tempDir.resolve("runs/run-1")

        writer.writeRunningMarker(runDir)

        val marker = runDir.resolve("_RUNNING")
        assertEquals(Instant.parse("2026-06-06T12:00:00Z").toString(), Files.readString(marker))
    }
}
