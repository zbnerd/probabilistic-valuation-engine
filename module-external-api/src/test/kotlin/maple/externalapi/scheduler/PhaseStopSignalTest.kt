package maple.externalapi.scheduler

import maple.externalapi.runstatus.PipelinePhase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class PhaseStopSignalTest {

    @Test
    fun `requestStop on idle phase returns true and trips flag`() {
        val signal = PhaseStopSignal()
        assertTrue(signal.requestStop(PipelinePhase.ITEM_EQUIPMENT))
        assertTrue(signal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `requestStop is idempotent — second call returns false (no state change)`() {
        val signal = PhaseStopSignal()
        assertTrue(signal.requestStop(PipelinePhase.ITEM_EQUIPMENT))
        assertFalse(signal.requestStop(PipelinePhase.ITEM_EQUIPMENT))
        assertTrue(signal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `isStopRequested on never-requested phase is false`() {
        val signal = PhaseStopSignal()
        assertFalse(signal.isStopRequested(PipelinePhase.OCID_LOOKUP))
    }

    @Test
    fun `clear resets flag to false`() {
        val signal = PhaseStopSignal()
        signal.requestStop(PipelinePhase.RANKING_FETCH)
        signal.clear(PipelinePhase.RANKING_FETCH)
        assertFalse(signal.isStopRequested(PipelinePhase.RANKING_FETCH))
    }

    @Test
    fun `clear on never-requested phase is no-op`() {
        val signal = PhaseStopSignal()
        signal.clear(PipelinePhase.CHARACTER_BASIC)
        assertFalse(signal.isStopRequested(PipelinePhase.CHARACTER_BASIC))
    }

    @Test
    fun `flags are per-phase — one phase's stop does not affect another`() {
        val signal = PhaseStopSignal()
        signal.requestStop(PipelinePhase.ITEM_EQUIPMENT)
        assertFalse(signal.isStopRequested(PipelinePhase.OCID_LOOKUP))
    }
}
