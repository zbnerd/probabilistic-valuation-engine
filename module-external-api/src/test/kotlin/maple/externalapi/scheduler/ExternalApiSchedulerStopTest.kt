package maple.externalapi.scheduler

import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.metrics.SchedulerMetrics
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.phase.CharacterBasicFetchPhase
import maple.externalapi.scheduler.phase.ItemEquipmentFetchPhase
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import maple.externalapi.scheduler.phase.RunIdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider

/**
 * Tests for the phase-stop branch in [ExternalApiScheduler]:
 *   - `requestPhaseStop` returns true only when a non-terminal run is in the slot
 *   - `runItemEquipmentPhase` `whenComplete` handles `PhaseStoppedException` →
 *     `stopRun` + signal clear, and clears the signal on every terminal path
 *     (success, generic failure, stop).
 */
class ExternalApiSchedulerStopTest {

    @Test
    fun `requestPhaseStop returns true when phase slot has non-terminal run`() {
        val runStatusTracker = realRunStatusTracker()
        val scheduler = itemEquipmentScheduler(runStatusTracker)

        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        assertTrue(scheduler.requestPhaseStop(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `requestPhaseStop returns false when phase slot empty`() {
        val runStatusTracker = realRunStatusTracker()
        val scheduler = itemEquipmentScheduler(runStatusTracker)

        assertFalse(scheduler.requestPhaseStop(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `requestPhaseStop returns false when phase slot already terminal`() {
        val runStatusTracker = realRunStatusTracker()
        val scheduler = itemEquipmentScheduler(runStatusTracker)

        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        runStatusTracker.completeRun(PipelinePhase.ITEM_EQUIPMENT, "run-1", 0, 0L)
        assertFalse(scheduler.requestPhaseStop(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `runItemEquipmentPhase whenComplete catches PhaseStoppedException to stopRun + signal cleared`() {
        val runStatusTracker = realRunStatusTracker()
        val stopSignal = PhaseStopSignal()
        val itemEquipmentPhase = mock<ItemEquipmentFetchPhase>()
        whenever(itemEquipmentPhase.execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), any<String>()))
            .thenReturn(CompletableFuture.failedFuture(PhaseStoppedException(PipelinePhase.ITEM_EQUIPMENT)))

        val scheduler = itemEquipmentScheduler(runStatusTracker, stopSignal, itemEquipmentPhase, ocidEntries = mapOf("ign1" to "ocid1"))

        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        scheduler.requestPhaseStop(PipelinePhase.ITEM_EQUIPMENT)
        assertTrue(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))

        try {
            scheduler.runItemEquipmentPhase("run-1", "upstream-run").get()
        } catch (ex: Exception) {
            // Expected: whenComplete observes PhaseStoppedException; .get() surfaces it.
        }

        val status = runStatusTracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)
        assertEquals(PipelinePhase.STOPPED, status!!.phase)
        assertTrue(status.isTerminal)
        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT), "signal must be cleared")
    }

    @Test
    fun `runItemEquipmentPhase success path clears signal`() {
        val runStatusTracker = realRunStatusTracker()
        val stopSignal = PhaseStopSignal()
        val itemEquipmentPhase = mock<ItemEquipmentFetchPhase>()
        whenever(itemEquipmentPhase.execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(Unit))

        val scheduler = itemEquipmentScheduler(runStatusTracker, stopSignal, itemEquipmentPhase, ocidEntries = mapOf("ign1" to "ocid1"))

        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        stopSignal.requestStop(PipelinePhase.ITEM_EQUIPMENT)

        scheduler.runItemEquipmentPhase("run-1", "upstream-run").get()

        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
        assertEquals(PipelinePhase.COMPLETED, runStatusTracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)!!.phase)
    }

    @Test
    fun `runItemEquipmentPhase generic failure path clears signal`() {
        val runStatusTracker = realRunStatusTracker()
        val stopSignal = PhaseStopSignal()
        val itemEquipmentPhase = mock<ItemEquipmentFetchPhase>()
        whenever(itemEquipmentPhase.execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), any<String>()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("nexon down")))

        val scheduler = itemEquipmentScheduler(runStatusTracker, stopSignal, itemEquipmentPhase, ocidEntries = mapOf("ign1" to "ocid1"))

        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        stopSignal.requestStop(PipelinePhase.ITEM_EQUIPMENT)

        try {
            scheduler.runItemEquipmentPhase("run-1", "upstream-run").get()
        } catch (ex: Exception) {
            // Expected: generic failure path surfaces the cause.
        }

        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
        // Generic failure path calls failRun (slot becomes FAILED) then
        // releasePhaseSlot (slot cleared for re-acquire). The two state
        // transitions are observable in sequence via the tracker mock.
    }

    private fun realRunStatusTracker(): RunStatusTracker = RunStatusTracker()

    private fun itemEquipmentScheduler(
        runStatusTracker: RunStatusTracker,
        stopSignal: PhaseStopSignal = PhaseStopSignal(),
        itemEquipmentPhase: ItemEquipmentFetchPhase = mock<ItemEquipmentFetchPhase>().also {
            whenever(it.execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), any<String>()))
                .thenReturn(CompletableFuture.completedFuture(Unit))
        },
        ocidEntries: Map<String, String> = mapOf("ign1" to "ocid1"),
    ): ExternalApiScheduler {
        val ocidCache = mock<OcidCacheProvider>()
        whenever(ocidCache.loadFromRun(any<String>())).thenReturn(ocidEntries)
        val schedulerMetrics = mock<SchedulerMetrics>()
        whenever(schedulerMetrics.drainRunChunks()).thenReturn(0L)
        whenever(schedulerMetrics.drainRunRecords()).thenReturn(0L)
        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        whenever(itemEquipmentProvider.ifAvailable).thenReturn(itemEquipmentPhase)
        return ExternalApiScheduler(
            ocidLookupPhase = mock<OcidLookupPhase>(),
            ocidCacheProvider = ocidCache,
            rankingFetchPhaseProvider = mock<ObjectProvider<RankingFetchPhase>>().also { whenever(it.ifAvailable).thenReturn(null) },
            characterBasicPhaseProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>().also { whenever(it.ifAvailable).thenReturn(null) },
            itemEquipmentFetchPhaseProvider = itemEquipmentProvider,
            schedulerMetrics = schedulerMetrics,
            runStatusTracker = runStatusTracker,
            runIdGenerator = RunIdGenerator(Clock.systemUTC()),
            runOnStartup = false,
            skipCharacterBasic = false,
            stopSignal = stopSignal,
        )
    }
}
