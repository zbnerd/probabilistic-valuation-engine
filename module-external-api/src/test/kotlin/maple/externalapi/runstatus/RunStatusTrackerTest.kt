package maple.externalapi.runstatus

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunStatusTrackerTest {

    private val fixedInstant = Instant.parse("2026-06-18T05:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val tracker = RunStatusTracker(clock)

    private fun advancingTracker(): RunStatusTracker {
        val nanos = AtomicLong(0L)
        val advancingClock = object : Clock() {
            override fun getZone(): ZoneOffset = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(1_000_000L + nanos.incrementAndGet())
        }
        return RunStatusTracker(advancingClock)
    }

    @Test
    fun `all slots empty initially`() {
        assertThat(tracker.getCurrentStatus()).isNull()
        assertThat(tracker.getLastCompletedRun()).isNull()
        for (phase in listOf(PipelinePhase.RANKING_FETCH, PipelinePhase.OCID_LOOKUP, PipelinePhase.CHARACTER_BASIC, PipelinePhase.ITEM_EQUIPMENT)) {
            assertThat(tracker.getPhaseStatus(phase)).isNull()
        }
    }

    @Test
    fun `acquirePhaseSlot succeeds when slot empty`() {
        val acquired = tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-ocid-1")
        assertThat(acquired).isNotNull
        assertThat(acquired!!.runId).isEqualTo("run-ocid-1")
        assertThat(acquired.phase).isEqualTo(PipelinePhase.OCID_LOOKUP)
        assertThat(acquired.triggeredPhase).isEqualTo(PipelinePhase.OCID_LOOKUP)
        assertThat(tracker.getPhaseStatus(PipelinePhase.OCID_LOOKUP)?.runId).isEqualTo("run-ocid-1")
    }

    @Test
    fun `acquirePhaseSlot returns null when slot has non-terminal run`() {
        tracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, "run-cb-1")
        val second = tracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, "run-cb-2")
        assertThat(second).isNull()
    }

    @Test
    fun `acquirePhaseSlot overwrites terminal record`() {
        // First run completes
        tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-1")
        tracker.completeRun(PipelinePhase.RANKING_FETCH, "run-r-1", 50, 100_000L)
        // Slot now has terminal record; second acquire should succeed (overwrite)
        val second = tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-2")
        assertThat(second).isNotNull
        assertThat(second!!.runId).isEqualTo("run-r-2")
        assertThat(second.isTerminal).isFalse()
    }

    @Test
    fun `completeRun sets phase to COMPLETED with chunks and records but keeps slot`() {
        tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-1")
        tracker.completeRun(PipelinePhase.RANKING_FETCH, "run-r-1", 50, 100_000L)
        val status = tracker.getPhaseStatus(PipelinePhase.RANKING_FETCH)!!
        assertThat(status.phase).isEqualTo(PipelinePhase.COMPLETED)
        assertThat(status.isTerminal).isTrue()
        assertThat(status.chunksProcessed).isEqualTo(50)
        assertThat(status.recordsProcessed).isEqualTo(100_000L)
        assertThat(status.completedAt).isNotNull
        // Slot NOT cleared — record persists
        assertThat(tracker.getPhaseStatus(PipelinePhase.RANKING_FETCH)).isNotNull
    }

    @Test
    fun `failRun sets phase to FAILED with errorMessage`() {
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o-1")
        tracker.failRun(PipelinePhase.OCID_LOOKUP, "run-o-1", "Nexon API timeout")
        val status = tracker.getPhaseStatus(PipelinePhase.OCID_LOOKUP)!!
        assertThat(status.phase).isEqualTo(PipelinePhase.FAILED)
        assertThat(status.errorMessage).isEqualTo("Nexon API timeout")
    }

    @Test
    fun `failRun is followed by releasePhaseSlot to clear slot`() {
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o-1")
        tracker.failRun(PipelinePhase.OCID_LOOKUP, "run-o-1", "boom")
        tracker.releasePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o-1")
        assertThat(tracker.getPhaseStatus(PipelinePhase.OCID_LOOKUP)).isNull()
    }

    @Test
    fun `transitionPhase preserves triggeredPhase and updates phase`() {
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o-1")
        tracker.transitionPhase(PipelinePhase.OCID_LOOKUP, "run-o-1")
        val status = tracker.getPhaseStatus(PipelinePhase.OCID_LOOKUP)!!
        assertThat(status.phase).isEqualTo(PipelinePhase.OCID_LOOKUP)
        assertThat(status.triggeredPhase).isEqualTo(PipelinePhase.OCID_LOOKUP)
    }

    @Test
    fun `releasePhaseSlot is no-op when runId mismatch`() {
        tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-1")
        tracker.releasePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-2")
        assertThat(tracker.getPhaseStatus(PipelinePhase.RANKING_FETCH)?.runId).isEqualTo("run-r-1")
    }

    @Test
    fun `hasNonTerminalRun returns the run when slot non-terminal`() {
        tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-ie-1")
        assertThat(tracker.hasNonTerminalRun(PipelinePhase.ITEM_EQUIPMENT)?.runId).isEqualTo("run-ie-1")
    }

    @Test
    fun `hasNonTerminalRun returns null after completeRun (terminal record kept but not active)`() {
        tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-ie-1")
        tracker.completeRun(PipelinePhase.ITEM_EQUIPMENT, "run-ie-1", 10, 1_000L)
        assertThat(tracker.hasNonTerminalRun(PipelinePhase.ITEM_EQUIPMENT)).isNull()
    }

    @Test
    fun `per-phase slots are independent`() {
        tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r")
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o")
        tracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, "run-cb")
        tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-ie")
        assertThat(tracker.getPhaseStatus(PipelinePhase.RANKING_FETCH)?.runId).isEqualTo("run-r")
        assertThat(tracker.getPhaseStatus(PipelinePhase.OCID_LOOKUP)?.runId).isEqualTo("run-o")
        assertThat(tracker.getPhaseStatus(PipelinePhase.CHARACTER_BASIC)?.runId).isEqualTo("run-cb")
        assertThat(tracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)?.runId).isEqualTo("run-ie")
    }

    @Test
    fun `getCurrentStatus returns the most recently started non-terminal run across phases`() {
        val tracker = advancingTracker()
        tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r")
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o")
        val current = tracker.getCurrentStatus()
        assertThat(current?.runId).isEqualTo("run-o")
    }

    @Test
    fun `getLastCompletedRun returns the most recent terminal run across phases`() {
        val tracker = advancingTracker()
        tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r")
        tracker.completeRun(PipelinePhase.RANKING_FETCH, "run-r", 10, 1_000L)
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o")
        tracker.completeRun(PipelinePhase.OCID_LOOKUP, "run-o", 20, 2_000L)
        val last = tracker.getLastCompletedRun()
        assertThat(last?.runId).isEqualTo("run-o")
    }

    @Test
    fun `stopRun sets phase to STOPPED with terminal=true and persists slot record`() {
        val acquired = tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        assertThat(acquired).isNotNull

        tracker.stopRun(PipelinePhase.ITEM_EQUIPMENT, "run-1", chunksProcessed = 42, recordsProcessed = 1000L)

        val status = tracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)
        assertThat(status).isNotNull
        assertThat(status!!.phase).isEqualTo(PipelinePhase.STOPPED)
        assertThat(status.isTerminal).isTrue()
        assertThat(status.chunksProcessed).isEqualTo(42)
        assertThat(status.recordsProcessed).isEqualTo(1000L)
        assertThat(status.completedAt).isEqualTo(fixedInstant)
    }

    @Test
    fun `stopRun with mismatched runId is a no-op`() {
        tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        tracker.stopRun(PipelinePhase.ITEM_EQUIPMENT, "different-run", 0, 0L)

        val status = tracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)
        assertThat(status!!.phase).isEqualTo(PipelinePhase.ITEM_EQUIPMENT)
        assertThat(status.isTerminal).isFalse()
    }

    @Test
    fun `acquirePhaseSlot after stopRun succeeds (terminal-overwrite)`() {
        tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        tracker.stopRun(PipelinePhase.ITEM_EQUIPMENT, "run-1", 0, 0L)
        assertThat(tracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)!!.isTerminal).isTrue()

        val newAcquire = tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-2")
        assertThat(newAcquire).isNotNull
        assertThat(newAcquire!!.runId).isEqualTo("run-2")
        assertThat(newAcquire.phase).isEqualTo(PipelinePhase.ITEM_EQUIPMENT)
    }

    @Test
    fun `hasNonTerminalRun returns null after stopRun (allows next trigger)`() {
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-1")
        tracker.stopRun(PipelinePhase.OCID_LOOKUP, "run-1", 0, 0L)

        assertThat(tracker.hasNonTerminalRun(PipelinePhase.OCID_LOOKUP)).isNull()
    }

    @Test
    fun `stopRun on empty slot is no-op`() {
        tracker.stopRun(PipelinePhase.RANKING_FETCH, "phantom", 0, 0L)
        assertThat(tracker.getPhaseStatus(PipelinePhase.RANKING_FETCH)).isNull()
    }

    @Test
    fun `acquirePhaseSlot with loopId stores loopId on slot record`() {
        val acquired = tracker.acquirePhaseSlot(
            phase = PipelinePhase.ITEM_EQUIPMENT,
            runId = "run-1",
            loopId = "L-1",
        )
        assertThat(acquired).isNotNull
        assertThat(acquired!!.loopId).isEqualTo("L-1")
        assertThat(tracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)?.loopId).isEqualTo("L-1")
    }

    @Test
    fun `acquirePhaseSlot without loopId leaves loopId null on slot record`() {
        val acquired = tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        assertThat(acquired).isNotNull
        assertThat(acquired!!.loopId).isNull()
    }

    @Test
    fun `acquirePhaseSlot overwrites loopId on terminal-overwrite acquire`() {
        // First loop iteration completes; terminal record persists with loopId
        tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1", "L-1")
        tracker.completeRun(PipelinePhase.ITEM_EQUIPMENT, "run-1", 0, 0L)

        // Next iteration within same loop overwrites the terminal record
        val next = tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-2", "L-1")
        assertThat(next).isNotNull
        assertThat(next!!.loopId).isEqualTo("L-1")
        assertThat(next.runId).isEqualTo("run-2")
    }
}
