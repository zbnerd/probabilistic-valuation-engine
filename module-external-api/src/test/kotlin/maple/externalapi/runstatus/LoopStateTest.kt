package maple.externalapi.runstatus

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LoopStateTest {

    @Test
    fun `LoopStatus enum has three states in lifecycle order`() {
        val values = LoopStatus.values().map { it.name }
        assertEquals(listOf("RUNNING", "STOPPING", "STOPPED"), values)
    }

    @Test
    fun `new LoopState defaults to RUNNING with zero iterations and no last runId`() {
        val state = LoopState(
            loopId = "L-1",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.parse("2026-06-19T00:00:00Z"),
        )
        assertEquals(LoopStatus.RUNNING, state.status)
        assertEquals(0, state.iterationCount)
        assertNull(state.lastRunId)
        assertNull(state.lastError)
    }

    @Test
    fun `LoopState mutates status, iterationCount, lastRunId, lastError in place`() {
        val state = LoopState(
            loopId = "L-1",
            phase = PipelinePhase.OCID_LOOKUP,
            startedAt = Instant.parse("2026-06-19T00:00:00Z"),
        )
        state.iterationCount = 3
        state.lastRunId = "run-3"
        state.lastError = "boom"
        state.status = LoopStatus.STOPPING

        assertEquals(3, state.iterationCount)
        assertEquals("run-3", state.lastRunId)
        assertEquals("boom", state.lastError)
        assertEquals(LoopStatus.STOPPING, state.status)
    }
}
