package maple.externalapi.runstatus

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
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
}
