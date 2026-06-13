package maple.externalapi.runstatus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunStatusTrackerTest {

    private val tracker = RunStatusTracker()

    @Test
    fun `initial status is null`() {
        val status = tracker.getCurrentStatus()
        assertThat(status).isNull()
    }

    @Test
    fun `startRun creates RUNNING status`() {
        val runId = java.util.UUID.randomUUID().toString()
        tracker.startRun(runId)

        val status = tracker.getCurrentStatus()!!
        assertThat(status.runId).isEqualTo(runId)
        assertThat(status.phase).isEqualTo(PipelinePhase.RANKING_FETCH)
        assertThat(status.isTerminal).isFalse()
    }

    @Test
    fun `transitionPhase updates phase`() {
        tracker.startRun("run-1")
        tracker.transitionPhase(PipelinePhase.OCID_LOOKUP)

        val status = tracker.getCurrentStatus()!!
        assertThat(status.phase).isEqualTo(PipelinePhase.OCID_LOOKUP)
    }

    @Test
    fun `completeRun sets COMPLETED`() {
        tracker.startRun("run-1")
        tracker.transitionPhase(PipelinePhase.OCID_LOOKUP)
        tracker.completeRun("run-1", 100, 600000L)

        val status = tracker.getCurrentStatus()!!
        assertThat(status.phase).isEqualTo(PipelinePhase.COMPLETED)
        assertThat(status.isTerminal).isTrue()
        assertThat(status.chunksProcessed).isEqualTo(100)
        assertThat(status.recordsProcessed).isEqualTo(600000L)
        assertThat(status.completedAt).isNotNull()
    }

    @Test
    fun `failRun sets FAILED with message`() {
        tracker.startRun("run-1")
        tracker.failRun("run-1", "Nexon API timeout")

        val status = tracker.getCurrentStatus()!!
        assertThat(status.phase).isEqualTo(PipelinePhase.FAILED)
        assertThat(status.errorMessage).isEqualTo("Nexon API timeout")
    }

    @Test
    fun `CHARACTER_BASIC_DONE is non-terminal`() {
        // Regression: the new intermediate phase must NOT be reported as terminal
        // by the run-status API; otherwise /api/internal/run-status shows
        // terminal=true while ITEM_EQUIPMENT is still in flight.
        tracker.startRun("run-1")
        tracker.transitionPhase(PipelinePhase.CHARACTER_BASIC)
        tracker.transitionPhase(PipelinePhase.CHARACTER_BASIC_DONE)

        val status = tracker.getCurrentStatus()!!
        assertThat(status.phase).isEqualTo(PipelinePhase.CHARACTER_BASIC_DONE)
        assertThat(status.isTerminal).isFalse()
    }

    @Test
    fun `getLastCompletedRun returns most recent completed`() {
        tracker.startRun("run-1")
        tracker.completeRun("run-1", 10, 1000L)

        Thread.sleep(10)

        tracker.startRun("run-2")
        tracker.transitionPhase(PipelinePhase.OCID_LOOKUP)

        val last = tracker.getLastCompletedRun()!!
        assertThat(last.runId).isEqualTo("run-1")
        assertThat(last.phase).isEqualTo(PipelinePhase.COMPLETED)
    }

    @Test
    fun `startItemEquipmentCycle sets initial phase to ITEM_EQUIPMENT not RANKING_FETCH`() {
        // Regression for the bug where ItemEquipmentContinuousLoop called
        // startRun(), which hardcoded phase=RANKING_FETCH, leaving the
        // run-status API displaying a misleading "ranking" label for the
        // entire item-equipment cycle.
        val runId = "item-equip-cycle-1"
        tracker.startItemEquipmentCycle(runId)

        val status = tracker.getCurrentStatus()!!
        assertThat(status.runId).isEqualTo(runId)
        assertThat(status.phase).isEqualTo(PipelinePhase.ITEM_EQUIPMENT)
        assertThat(status.isTerminal).isFalse()
    }

    @Test
    fun `startItemEquipmentCycle replaces a previous run-status cleanly`() {
        // Multiple item-equipment cycles run sequentially; each new cycle
        // must overwrite the previous run-status so /run-status reflects
        // the in-flight cycle, not the previous one's terminal state.
        tracker.startItemEquipmentCycle("cycle-1")
        tracker.transitionPhase(PipelinePhase.ITEM_EQUIPMENT)
        tracker.completeRun("cycle-1", 50, 100000L)

        val last = tracker.getLastCompletedRun()!!
        assertThat(last.runId).isEqualTo("cycle-1")
        assertThat(last.phase).isEqualTo(PipelinePhase.COMPLETED)

        tracker.startItemEquipmentCycle("cycle-2")
        val current = tracker.getCurrentStatus()!!
        assertThat(current.runId).isEqualTo("cycle-2")
        assertThat(current.phase).isEqualTo(PipelinePhase.ITEM_EQUIPMENT)
        assertThat(current.isTerminal).isFalse()
    }
}
