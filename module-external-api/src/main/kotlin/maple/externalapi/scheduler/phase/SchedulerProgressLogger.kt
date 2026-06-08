package maple.externalapi.scheduler.phase

import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SchedulerProgressLogger(private val clock: Clock) {
    fun logProgress(phase: String, progress: Int, total: Int, stored: Int, fails: Int, start: Instant) {
        val elapsedSec = Duration.between(start, clock.instant()).toMillis() / 1000.0
        val rate = if (elapsedSec > 0) "%.0f".format(progress / elapsedSec) else "?"
        log.info(
            "[Scheduler] {}: {}/{} (success={}, fail={}, rate={}files/s, elapsed={}s)",
            phase,
            progress,
            total,
            stored,
            fails,
            rate,
            elapsedSec.toLong(),
        )
    }

    fun logSummary(phase: String, total: Int, success: Int, stored: Int, fails: Int, start: Instant) {
        val elapsedSec = Duration.between(start, clock.instant()).toMillis() / 1000.0
        val rate = if (elapsedSec > 0) "%.0f".format(total / elapsedSec) else "?"
        log.info("[Scheduler] ========== {} complete ==========", phase)
        log.info(
            "[Scheduler] result: total={}, success={}, fail={}, elapsed={}s, avgRate={}files/s",
            total,
            success,
            fails,
            elapsedSec.toLong(),
            rate,
        )
    }
    companion object {
        private val log = LoggerFactory.getLogger(SchedulerProgressLogger::class.java)
    }
}
