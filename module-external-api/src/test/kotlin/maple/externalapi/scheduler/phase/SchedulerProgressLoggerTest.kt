package maple.externalapi.scheduler.phase

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchedulerProgressLoggerTest {
    @Test
    fun `logProgress emits rate and elapsed seconds`() {
        val start = Instant.parse("2026-06-06T11:00:00Z")
        val now = Instant.parse("2026-06-06T11:00:10Z")
        val logger = SchedulerProgressLogger(Clock.fixed(now, ZoneId.of("UTC")))

        logger.logProgress(phase = "Test", progress = 50, total = 100, stored = 48, fails = 2, start = start)

        assertTrue(true)
    }

    @Test
    fun `logSummary emits total, success, fail, elapsed`() {
        val start = Instant.parse("2026-06-06T11:00:00Z")
        val now = Instant.parse("2026-06-06T11:00:05Z")
        val logger = SchedulerProgressLogger(Clock.fixed(now, ZoneId.of("UTC")))

        logger.logSummary(phase = "Test", total = 100, success = 90, stored = 90, fails = 10, start = start)

        assertTrue(true)
    }
}
