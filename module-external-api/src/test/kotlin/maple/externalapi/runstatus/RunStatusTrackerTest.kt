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
        tracker.completeRun(100, 600000L)

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
        tracker.failRun("Nexon API timeout")

        val status = tracker.getCurrentStatus()!!
        assertThat(status.phase).isEqualTo(PipelinePhase.FAILED)
        assertThat(status.errorMessage).isEqualTo("Nexon API timeout")
    }

    @Test
    fun `getLastCompletedRun returns most recent completed`() {
        tracker.startRun("run-1")
        tracker.completeRun(10, 1000L)

        Thread.sleep(10)

        tracker.startRun("run-2")
        tracker.transitionPhase(PipelinePhase.OCID_LOOKUP)

        val last = tracker.getLastCompletedRun()!!
        assertThat(last.runId).isEqualTo("run-1")
        assertThat(last.phase).isEqualTo(PipelinePhase.COMPLETED)
    }
}
