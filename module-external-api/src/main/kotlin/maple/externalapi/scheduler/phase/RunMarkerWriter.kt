package maple.externalapi.scheduler.phase

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class RunMarkerWriter(private val clock: Clock) {
    fun writeRunningMarker(runDir: Path) {
        val marker = runDir.resolve("_RUNNING")
        Files.createDirectories(runDir)
        Files.writeString(marker, clock.instant().toString())
        log.info("[Scheduler] wrote _RUNNING marker: {}", marker)
    }
    companion object {
        private val log = LoggerFactory.getLogger(RunMarkerWriter::class.java)
    }
}
