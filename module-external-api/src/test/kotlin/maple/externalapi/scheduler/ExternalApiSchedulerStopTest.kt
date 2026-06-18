package maple.externalapi.scheduler

import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.runBlocking
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
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
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

    @Test
    fun `runRankingPhase whenComplete catches PhaseStoppedException to stopRun + signal cleared`() {
        val runStatusTracker = realRunStatusTracker()
        val stopSignal = PhaseStopSignal()
        val rankingPhase = mock<RankingFetchPhase>()
        whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
            .thenReturn(CompletableFuture.failedFuture(PhaseStoppedException(PipelinePhase.RANKING_FETCH)))

        val scheduler = allPhasesScheduler(runStatusTracker, stopSignal, rankingPhase = rankingPhase)

        runStatusTracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r")
        scheduler.requestPhaseStop(PipelinePhase.RANKING_FETCH)
        assertTrue(stopSignal.isStopRequested(PipelinePhase.RANKING_FETCH))

        try {
            scheduler.runRankingPhase("run-r", null).join()
        } catch (ex: Exception) {
            // Expected: whenComplete observes PhaseStoppedException; .join() surfaces it.
        }

        val status = runStatusTracker.getPhaseStatus(PipelinePhase.RANKING_FETCH)
        assertEquals(PipelinePhase.STOPPED, status!!.phase)
        assertTrue(status.isTerminal)
        assertFalse(stopSignal.isStopRequested(PipelinePhase.RANKING_FETCH), "signal must be cleared")
    }

    @Test
    fun `runOcidPhase whenComplete catches PhaseStoppedException to stopRun + signal cleared`() {
        val runStatusTracker = realRunStatusTracker()
        val stopSignal = PhaseStopSignal()
        val ocidLookupPhase = mock<OcidLookupPhase>()
        // runOcidPhase calls runBlocking { ocidLookupPhase.execute(...) }. Stub the
        // suspend function to throw PhaseStoppedException; runBlocking propagates it
        // synchronously and the outer runCatching converts it to a failed CF.
        runBlocking {
            wheneverBlocking { ocidLookupPhase.execute(any<ExecutorService>(), any<String>(), any<String>()) }
                .doThrow(PhaseStoppedException(PipelinePhase.OCID_LOOKUP))
        }

        val scheduler = allPhasesScheduler(runStatusTracker, stopSignal, ocidLookupPhase = ocidLookupPhase)

        runStatusTracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o")
        scheduler.requestPhaseStop(PipelinePhase.OCID_LOOKUP)
        assertTrue(stopSignal.isStopRequested(PipelinePhase.OCID_LOOKUP))

        try {
            scheduler.runOcidPhase("run-o", "upstream-run").join()
        } catch (ex: Exception) {
            // Expected: whenComplete observes PhaseStoppedException; .join() surfaces it.
        }

        val status = runStatusTracker.getPhaseStatus(PipelinePhase.OCID_LOOKUP)
        assertEquals(PipelinePhase.STOPPED, status!!.phase)
        assertTrue(status.isTerminal)
        assertFalse(stopSignal.isStopRequested(PipelinePhase.OCID_LOOKUP), "signal must be cleared")
    }

    @Test
    fun `runCharBasicPhase whenComplete catches PhaseStoppedException to stopRun + signal cleared`() {
        val runStatusTracker = realRunStatusTracker()
        val stopSignal = PhaseStopSignal()
        val characterBasicPhase = mock<CharacterBasicFetchPhase>()
        whenever(characterBasicPhase.execute(any<ExecutorService>(), any<Map<String, String>>(), any()))
            .thenReturn(CompletableFuture.failedFuture(PhaseStoppedException(PipelinePhase.CHARACTER_BASIC)))

        val scheduler = allPhasesScheduler(runStatusTracker, stopSignal, characterBasicPhase = characterBasicPhase)

        runStatusTracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, "run-cb")
        scheduler.requestPhaseStop(PipelinePhase.CHARACTER_BASIC)
        assertTrue(stopSignal.isStopRequested(PipelinePhase.CHARACTER_BASIC))

        try {
            scheduler.runCharBasicPhase("run-cb", "upstream-run").join()
        } catch (ex: Exception) {
            // Expected: whenComplete observes PhaseStoppedException; .join() surfaces it.
        }

        val status = runStatusTracker.getPhaseStatus(PipelinePhase.CHARACTER_BASIC)
        assertEquals(PipelinePhase.STOPPED, status!!.phase)
        assertTrue(status.isTerminal)
        assertFalse(stopSignal.isStopRequested(PipelinePhase.CHARACTER_BASIC), "signal must be cleared")
    }

    private fun realRunStatusTracker(): RunStatusTracker = RunStatusTracker()

    /**
     * Scheduler factory with all 4 phase beans wired (mocked). Unlike [itemEquipmentScheduler]
     * which defaults the other providers to null, this lets each per-phase stop test stub the
     * bean it cares about while leaving the others on inert defaults.
     */
    private fun allPhasesScheduler(
        runStatusTracker: RunStatusTracker,
        stopSignal: PhaseStopSignal = PhaseStopSignal(),
        ocidLookupPhase: OcidLookupPhase = mock<OcidLookupPhase>(),
        rankingPhase: RankingFetchPhase = mock<RankingFetchPhase>().also {
            whenever(it.execute(any<ExecutorService>(), any<String>()))
                .thenReturn(CompletableFuture.completedFuture("runs/run-r"))
        },
        characterBasicPhase: CharacterBasicFetchPhase = mock<CharacterBasicFetchPhase>().also {
            whenever(it.execute(any<ExecutorService>(), any<Map<String, String>>(), any()))
                .thenReturn(CompletableFuture.completedFuture(Unit))
        },
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
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(characterBasicPhase)
        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        whenever(itemEquipmentProvider.ifAvailable).thenReturn(itemEquipmentPhase)
        return ExternalApiScheduler(
            ocidLookupPhase = ocidLookupPhase,
            ocidCacheProvider = ocidCache,
            rankingFetchPhaseProvider = rankingProvider,
            characterBasicPhaseProvider = charBasicProvider,
            itemEquipmentFetchPhaseProvider = itemEquipmentProvider,
            schedulerMetrics = schedulerMetrics,
            runStatusTracker = runStatusTracker,
            runIdGenerator = RunIdGenerator(Clock.systemUTC()),
            runOnStartup = false,
            skipCharacterBasic = false,
            stopSignal = stopSignal,
        )
    }

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
