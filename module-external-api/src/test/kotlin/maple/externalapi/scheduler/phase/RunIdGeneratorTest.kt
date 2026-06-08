package maple.externalapi.scheduler.phase

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RunIdGeneratorTest {
    @Test
    fun `newRunId uses clock instant and zone`() {
        val fixed = Clock.fixed(Instant.parse("2026-06-06T12:34:56Z"), ZoneId.of("UTC"))
        val generator = RunIdGenerator(fixed)

        val id = generator.newRunId()

        // Format: "yyyyMMdd-HHmmss-<nano>" using the fixed zone (UTC).
        // Instant.parse produces a nano-of-second of 0, so nano suffix is "0".
        assertEquals("20260606-123456-0", id)
    }
}
