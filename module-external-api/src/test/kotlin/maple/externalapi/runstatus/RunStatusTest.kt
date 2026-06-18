package maple.externalapi.runstatus

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import java.time.Instant

class RunStatusTest {

    private fun statusOf(phase: PipelinePhase): RunStatus = RunStatus(
        runId = "r",
        phase = phase,
        triggeredPhase = phase,
        startedAt = Instant.EPOCH,
    )

    @Test
    fun `COMPLETED is terminal`() {
        assertTrue(statusOf(PipelinePhase.COMPLETED).isTerminal)
    }

    @Test
    fun `FAILED is terminal`() {
        assertTrue(statusOf(PipelinePhase.FAILED).isTerminal)
    }

    @Test
    fun `STOPPED is terminal`() {
        assertTrue(statusOf(PipelinePhase.STOPPED).isTerminal)
    }

    @Test
    fun `RANKING_FETCH is not terminal`() {
        assertFalse(statusOf(PipelinePhase.RANKING_FETCH).isTerminal)
    }

    @Test
    fun `RunStatus loopId defaults to null for non-loop runs`() {
        val status = RunStatus(
            runId = "run-1",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.EPOCH,
        )
        assertNull(status.loopId)
    }

    @Test
    fun `RunStatus loopId can be set on construction for loop iterations`() {
        val status = RunStatus(
            runId = "run-1",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.EPOCH,
            loopId = "L-7",
        )
        assertEquals("L-7", status.loopId)
    }
}
