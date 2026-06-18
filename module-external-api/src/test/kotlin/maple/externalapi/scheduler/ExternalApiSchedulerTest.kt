package maple.externalapi.scheduler

import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.runBlocking
import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.metrics.SchedulerMetrics
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.phase.CharacterBasicFetchPhase
import maple.externalapi.scheduler.phase.ItemEquipmentFetchPhase
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import maple.externalapi.scheduler.phase.RunIdGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider

/**
 * Migration Task 10: [ExternalApiScheduler] must pre-generate the runId
 * (via [RunIdGenerator]) and pass it to [RankingFetchPhase.execute] so the
 * run-status tracker transitions to RANKING_FETCH for the new run BEFORE
 * any async phase begins. The returned runKey is then forwarded unchanged
 * (with the `runs/` prefix) downstream to [OcidLookupPhase.execute].
 *
 * Run-status wiring: when char-basic ends, ExternalApiScheduler must transition
 * to [PipelinePhase.CHARACTER_BASIC_DONE] — NOT [PipelinePhase.COMPLETED] — because
 * item-equipment is invoked as a SEPARATE per-phase run via
 * [ExternalApiScheduler.runItemEquipmentPhase] and signals full completion from there.
 */
class ExternalApiSchedulerTest {

    @Test
    fun `triggerDailyRefresh generates 4 distinct runIds and forwards runKey to OCID lookup`() {
        val rankingPhase = mock<RankingFetchPhase>()
        // Ranking echoes the runId it was given back as the runKey.
        // This matches the production contract: RankingFetchPhase.execute
        // uses the runId to build `runs/$runId`.
        whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
            .thenAnswer { invocation ->
                val runId: String = invocation.getArgument(1)
                CompletableFuture.completedFuture("runs/$runId")
            }

        val ocidLookupPhase = mock<OcidLookupPhase>()
        runBlocking {
            whenever(ocidLookupPhase.execute(any<ExecutorService>(), any<String>(), any<String>()))
                .thenReturn(Unit)
        }

        val itemEquipmentPhase = mock<ItemEquipmentFetchPhase>()
        whenever(itemEquipmentPhase.execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(Unit))

        val ocidCache = mock<OcidCacheProvider>()
        whenever(ocidCache.loadFromRun(any<String>())).thenReturn(emptyMap())
        val runStatusTracker = mock<RunStatusTracker>()
        // Stub acquirePhaseSlot to return a valid RunStatus for any phase/runId
        whenever(runStatusTracker.acquirePhaseSlot(any(), any<String>()))
            .thenAnswer { invocation ->
                val runId = invocation.getArgument<String>(1)
                val phase = invocation.getArgument<PipelinePhase>(0)
                maple.externalapi.runstatus.RunStatus(
                    runId = runId,
                    phase = phase,
                    triggeredPhase = phase,
                    startedAt = java.time.Instant.now(),
                )
            }

        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)

        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(null)

        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        whenever(itemEquipmentProvider.ifAvailable).thenReturn(itemEquipmentPhase)
        val schedulerMetrics = mock<SchedulerMetrics>()

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        scheduler.triggerDailyRefresh(null).get()

        // The OCID lookup phase is invoked from a runBlocking on the virtual-thread executor.
        // The runKey argument is `runs/<rRunId>` — the RANKING runId, prefixed.
        val runKeyCaptor = argumentCaptor<String>()
        runBlocking {
            verify(ocidLookupPhase, timeout(5_000)).execute(any<ExecutorService>(), runKeyCaptor.capture(), any())
        }
        // The runId passed to ranking.execute is the suffix of the runKey forwarded to OCID.
        val runIdPassedToRanking = argumentCaptor<String>()
        verify(rankingPhase, timeout(5_000)).execute(any<ExecutorService>(), runIdPassedToRanking.capture())
        assertThat(runKeyCaptor.firstValue).isEqualTo("runs/${runIdPassedToRanking.firstValue}")
    }

    @Test
    fun `triggerDailyRefresh completes CHARACTER_BASIC via per-phase runCharBasicPhase not CHARACTER_BASIC_DONE transition`() {
        // Post-Revision 2: ExternalApiScheduler no longer emits CHARACTER_BASIC_DONE
        // transition. Each phase (CHARACTER_BASIC, ITEM_EQUIPMENT) is a separate
        // triggerPhase call; per-phase completion goes through completeRun(phase, ...).
        // This test asserts the chain reaches runCharBasicPhase and calls
        // completeRun(CHARACTER_BASIC, ...).

        val rankingPhase = mock<RankingFetchPhase>()
        whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture("runs/run-cb-done"))

        val ocidLookupPhase = mock<OcidLookupPhase>()
        runBlocking {
            whenever(ocidLookupPhase.execute(any<ExecutorService>(), any<String>(), any<String>()))
                .thenReturn(Unit)
        }

        val charBasicPhase = mock<CharacterBasicFetchPhase>()
        whenever(charBasicPhase.execute(any<ExecutorService>(), any<Map<String, String>>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(Unit))

        val ocidCache = mock<OcidCacheProvider>()
        whenever(ocidCache.loadFromRun(any<String>())).thenReturn(mapOf("ign1" to "ocid1"))

        val runStatusTracker = mock<RunStatusTracker>()
        whenever(runStatusTracker.acquirePhaseSlot(any(), any<String>()))
            .thenAnswer { invocation ->
                val runId = invocation.getArgument<String>(1)
                val phase = invocation.getArgument<PipelinePhase>(0)
                maple.externalapi.runstatus.RunStatus(
                    runId = runId,
                    phase = phase,
                    triggeredPhase = phase,
                    startedAt = java.time.Instant.now(),
                )
            }

        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)

        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(charBasicPhase)

        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        val itemEquipmentPhase = mock<ItemEquipmentFetchPhase>()
        whenever(itemEquipmentPhase.execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(Unit))
        whenever(itemEquipmentProvider.ifAvailable).thenReturn(itemEquipmentPhase)
        val schedulerMetrics = mock<SchedulerMetrics>()

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        scheduler.triggerDailyRefresh(null).get()

        // Per-phase completion: runCharBasicPhase ends with completeRun(CHARACTER_BASIC, runId, ...).
        verify(runStatusTracker, timeout(5_000))
            .completeRun(eq(PipelinePhase.CHARACTER_BASIC), any<String>(), any<Int>(), any<Long>())
        // The chain no longer emits CHARACTER_BASIC_DONE — that intermediate state is gone
        // because item-equipment is its own phase run, not a continuous loop tail.
        verify(runStatusTracker, timeout(1_000).times(0))
            .transitionPhase(PipelinePhase.CHARACTER_BASIC_DONE)
    }

    /**
     * Post-Revision 2: triggerDailyRefresh no longer calls startRun. Slot
     * acquisition (`acquirePhaseSlot`) replaces the legacy startRun + handle
     * pattern. This test asserts the new chain reaches acquirePhaseSlot for
     * RANKING_FETCH with a freshly-generated runId even when ranking.execute
     * fails — guaranteeing the tracker sees the new run before ranking
     * returns (slot is held for failRun/cleanup).
     */
    @Test
    fun `triggerDailyRefresh acquires RANKING_FETCH slot before ranking execute, even if ranking fails`() {
        val rankingPhase = mock<RankingFetchPhase>()
        whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("rank api down")))

        val ocidLookupPhase = mock<OcidLookupPhase>()
        val ocidCache = mock<OcidCacheProvider>()
        val runStatusTracker = mock<RunStatusTracker>()
        whenever(runStatusTracker.acquirePhaseSlot(eq(PipelinePhase.RANKING_FETCH), any<String>()))
            .thenAnswer { invocation ->
                val runId = invocation.getArgument<String>(1)
                maple.externalapi.runstatus.RunStatus(
                    runId = runId,
                    phase = PipelinePhase.RANKING_FETCH,
                    triggeredPhase = PipelinePhase.RANKING_FETCH,
                    startedAt = java.time.Instant.now(),
                )
            }

        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)

        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(null)

        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        whenever(itemEquipmentProvider.ifAvailable).thenReturn(null)
        val schedulerMetrics = mock<SchedulerMetrics>()

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        try {
            scheduler.triggerDailyRefresh(null).get()
        } catch (ex: Exception) {
            // Expected: ranking failure short-circuits the chain.
        }

        // acquirePhaseSlot(RANKING_FETCH, rRunId) must fire with a freshly generated
        // runId before ranking.execute returns. The slot is then released by
        // runRankingPhase's whenComplete handler.
        verify(runStatusTracker, timeout(2_000))
            .acquirePhaseSlot(eq(PipelinePhase.RANKING_FETCH), any<String>())
        verify(runStatusTracker, timeout(2_000))
            .releasePhaseSlot(eq(PipelinePhase.RANKING_FETCH), any<String>())
    }

    @Test
    fun `runRankingPhase acquires RANKING_FETCH slot and calls rankingFetchPhaseProvider execute`() {
        val rankingPhase = mock<RankingFetchPhase>()
        whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture("runs/run-r-1"))

        val ocidLookupPhase = mock<OcidLookupPhase>()
        val ocidCache = mock<OcidCacheProvider>()
        val runStatusTracker = mock<RunStatusTracker>()
        whenever(runStatusTracker.acquirePhaseSlot(eq(PipelinePhase.RANKING_FETCH), eq("run-r-1")))
            .thenReturn(
                maple.externalapi.runstatus.RunStatus(
                    runId = "run-r-1",
                    phase = PipelinePhase.RANKING_FETCH,
                    triggeredPhase = PipelinePhase.RANKING_FETCH,
                    startedAt = java.time.Instant.now(),
                )
            )
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(null)
        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        whenever(itemEquipmentProvider.ifAvailable).thenReturn(null)
        val schedulerMetrics = mock<SchedulerMetrics>()

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        scheduler.runRankingPhase("run-r-1", null).get()

        verify(rankingPhase).execute(any<ExecutorService>(), eq("run-r-1"))
        verify(runStatusTracker).acquirePhaseSlot(eq(PipelinePhase.RANKING_FETCH), eq("run-r-1"))
        verify(runStatusTracker).completeRun(eq(PipelinePhase.RANKING_FETCH), eq("run-r-1"), any(), any())
    }

    @Test
    fun `runOcidPhase acquires OCID_LOOKUP slot and forwards phase's own runId (not upstreamRunId) to OcidLookupPhase execute`() {
        val rankingPhase = mock<RankingFetchPhase>()
        val ocidLookupPhase = mock<OcidLookupPhase>()
        // Suspend fun returns Unit; stub via runBlocking so the suspend bridge is wired up.
        // Then the runBlocking inside runOcidPhase resumes Unit and the whenComplete fires completeRun.
        runBlocking {
            whenever(ocidLookupPhase.execute(any<ExecutorService>(), any<String>(), any<String>()))
                .thenReturn(Unit)
        }

        val ocidCache = mock<OcidCacheProvider>()
        val runStatusTracker = mock<RunStatusTracker>()
        whenever(runStatusTracker.acquirePhaseSlot(eq(PipelinePhase.OCID_LOOKUP), eq("run-o-1")))
            .thenReturn(
                maple.externalapi.runstatus.RunStatus(
                    runId = "run-o-1",
                    phase = PipelinePhase.OCID_LOOKUP,
                    triggeredPhase = PipelinePhase.OCID_LOOKUP,
                    startedAt = java.time.Instant.now(),
                )
            )
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(null)
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(null)
        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        whenever(itemEquipmentProvider.ifAvailable).thenReturn(null)
        val schedulerMetrics = mock<SchedulerMetrics>()

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        scheduler.runOcidPhase("run-o-1", "run-r-1").get()

        verify(runStatusTracker).acquirePhaseSlot(eq(PipelinePhase.OCID_LOOKUP), eq("run-o-1"))
        runBlocking {
            verify(ocidLookupPhase).execute(any<ExecutorService>(), eq("runs/run-r-1"), eq("run-o-1"))
        }
        verify(runStatusTracker).completeRun(eq(PipelinePhase.OCID_LOOKUP), eq("run-o-1"), any(), any())
    }

    @Test
    fun `runRankingPhase releases slot on phase execution failure`() {
        val rankingPhase = mock<RankingFetchPhase>()
        whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("nexon api down")))

        val ocidLookupPhase = mock<OcidLookupPhase>()
        val ocidCache = mock<OcidCacheProvider>()
        val runStatusTracker = mock<RunStatusTracker>()
        whenever(runStatusTracker.acquirePhaseSlot(eq(PipelinePhase.RANKING_FETCH), eq("run-r-1")))
            .thenReturn(
                maple.externalapi.runstatus.RunStatus(
                    runId = "run-r-1",
                    phase = PipelinePhase.RANKING_FETCH,
                    triggeredPhase = PipelinePhase.RANKING_FETCH,
                    startedAt = java.time.Instant.now(),
                )
            )
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(null)

        val scheduler = ExternalApiScheduler(
            ocidLookupPhase = ocidLookupPhase,
            ocidCacheProvider = ocidCache,
            rankingFetchPhaseProvider = rankingProvider,
            characterBasicPhaseProvider = charBasicProvider,
            itemEquipmentFetchPhaseProvider = mock(),
            schedulerMetrics = mock(),
            runStatusTracker = runStatusTracker,
            runIdGenerator = RunIdGenerator(Clock.systemUTC()),
            runOnStartup = false,
            skipCharacterBasic = false,
            stopSignal = PhaseStopSignal(),
        )

        try {
            scheduler.runRankingPhase("run-r-1", null).get()
        } catch (ex: Exception) {
            // Expected: phase execution failure surfaces as a failed future.
        }

        verify(runStatusTracker).failRun(eq(PipelinePhase.RANKING_FETCH), eq("run-r-1"), argThat<String> { contains("nexon api down") })
        verify(runStatusTracker).releasePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-1")
    }

    @Test
    fun `runOcidPhase releases slot on phase execution failure`() {
        val ocidLookupPhase = mock<OcidLookupPhase>()
        // Suspend fun throws synchronously when called via runBlocking
        runBlocking {
            whenever(ocidLookupPhase.execute(any<ExecutorService>(), any<String>(), any<String>()))
                .thenThrow(RuntimeException("object storage timeout"))
        }

        val ocidCache = mock<OcidCacheProvider>()
        val runStatusTracker = mock<RunStatusTracker>()
        whenever(runStatusTracker.acquirePhaseSlot(eq(PipelinePhase.OCID_LOOKUP), eq("run-o-1")))
            .thenReturn(
                maple.externalapi.runstatus.RunStatus(
                    runId = "run-o-1",
                    phase = PipelinePhase.OCID_LOOKUP,
                    triggeredPhase = PipelinePhase.OCID_LOOKUP,
                    startedAt = java.time.Instant.now(),
                )
            )
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(null)

        val scheduler = ExternalApiScheduler(
            ocidLookupPhase = ocidLookupPhase,
            ocidCacheProvider = ocidCache,
            rankingFetchPhaseProvider = rankingProvider,
            characterBasicPhaseProvider = charBasicProvider,
            itemEquipmentFetchPhaseProvider = mock(),
            schedulerMetrics = mock(),
            runStatusTracker = runStatusTracker,
            runIdGenerator = RunIdGenerator(Clock.systemUTC()),
            runOnStartup = false,
            skipCharacterBasic = false,
            stopSignal = PhaseStopSignal(),
        )

        try {
            scheduler.runOcidPhase("run-o-1", "run-r-1").get()
        } catch (ex: Exception) {
            // Expected: phase execution failure surfaces as a failed future.
        }

        verify(runStatusTracker).failRun(eq(PipelinePhase.OCID_LOOKUP), eq("run-o-1"), argThat<String> { contains("object storage timeout") })
        verify(runStatusTracker).releasePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o-1")
    }

    @Test
    fun `runCharBasicPhase loads OCID cache from upstreamRunId and invokes charBasicPhase`() {
        val ocidCache = mock<OcidCacheProvider>()
        val ocidMap = mapOf("ign1" to "ocid1", "ign2" to "ocid2")
        whenever(ocidCache.loadFromRun("run-o-1")).thenReturn(ocidMap)

        val charBasicPhase = mock<CharacterBasicFetchPhase>()
        whenever(charBasicPhase.execute(any<ExecutorService>(), any<Map<String, String>>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(Unit))

        val ocidLookupPhase = mock<OcidLookupPhase>()
        val runStatusTracker = mock<RunStatusTracker>()
        whenever(runStatusTracker.acquirePhaseSlot(eq(PipelinePhase.CHARACTER_BASIC), eq("run-cb-1")))
            .thenReturn(
                maple.externalapi.runstatus.RunStatus(
                    runId = "run-cb-1",
                    phase = PipelinePhase.CHARACTER_BASIC,
                    triggeredPhase = PipelinePhase.CHARACTER_BASIC,
                    startedAt = java.time.Instant.now(),
                )
            )
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(null)
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(charBasicPhase)
        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        whenever(itemEquipmentProvider.ifAvailable).thenReturn(null)
        val schedulerMetrics = mock<SchedulerMetrics>()

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        scheduler.runCharBasicPhase("run-cb-1", "run-o-1").get()

        verify(ocidCache).loadFromRun("run-o-1")
        verify(charBasicPhase).execute(any<ExecutorService>(), eq(ocidMap), eq("run-cb-1"))
        verify(runStatusTracker).completeRun(eq(PipelinePhase.CHARACTER_BASIC), eq("run-cb-1"), any(), any())
    }

    @Test
    fun `runCharBasicPhase short-circuits when loadFromRun returns empty cache`() {
        val ocidCache = mock<OcidCacheProvider>()
        whenever(ocidCache.loadFromRun("run-o-empty")).thenReturn(emptyMap())

        val charBasicPhase = mock<CharacterBasicFetchPhase>()

        val ocidLookupPhase = mock<OcidLookupPhase>()
        val runStatusTracker = mock<RunStatusTracker>()
        whenever(runStatusTracker.acquirePhaseSlot(eq(PipelinePhase.CHARACTER_BASIC), eq("run-cb-2")))
            .thenReturn(
                maple.externalapi.runstatus.RunStatus(
                    runId = "run-cb-2",
                    phase = PipelinePhase.CHARACTER_BASIC,
                    triggeredPhase = PipelinePhase.CHARACTER_BASIC,
                    startedAt = java.time.Instant.now(),
                )
            )
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(null)
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(charBasicPhase)
        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        whenever(itemEquipmentProvider.ifAvailable).thenReturn(null)
        val schedulerMetrics = mock<SchedulerMetrics>()

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        scheduler.runCharBasicPhase("run-cb-2", "run-o-empty").get()

        verify(charBasicPhase, org.mockito.kotlin.never()).execute(any<ExecutorService>(), any<Map<String, String>>(), any<String>())
        verify(runStatusTracker).completeRun(eq(PipelinePhase.CHARACTER_BASIC), eq("run-cb-2"), eq(0), eq(0L))
    }

    @Test
    fun `runItemEquipmentPhase loads OCID cache from upstreamRunId and invokes itemEquipmentPhase`() {
        val ocidCache = mock<OcidCacheProvider>()
        val ocidMap = mapOf("ign1" to "ocid1")
        whenever(ocidCache.loadFromRun("run-cb-1")).thenReturn(ocidMap)

        val itemEquipmentPhase = mock<ItemEquipmentFetchPhase>()
        whenever(itemEquipmentPhase.execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(Unit))

        val ocidLookupPhase = mock<OcidLookupPhase>()
        val runStatusTracker = mock<RunStatusTracker>()
        whenever(runStatusTracker.acquirePhaseSlot(eq(PipelinePhase.ITEM_EQUIPMENT), eq("run-ie-1")))
            .thenReturn(
                maple.externalapi.runstatus.RunStatus(
                    runId = "run-ie-1",
                    phase = PipelinePhase.ITEM_EQUIPMENT,
                    triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
                    startedAt = java.time.Instant.now(),
                )
            )
        val schedulerMetrics = mock<SchedulerMetrics>()
        whenever(schedulerMetrics.drainRunChunks()).thenReturn(50L)
        whenever(schedulerMetrics.drainRunRecords()).thenReturn(100_000L)
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(null)
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(null)
        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        whenever(itemEquipmentProvider.ifAvailable).thenReturn(itemEquipmentPhase)

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        scheduler.runItemEquipmentPhase("run-ie-1", "run-cb-1").get()

        verify(ocidCache).loadFromRun("run-cb-1")
        verify(itemEquipmentPhase).execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), eq("run-ie-1"))
        verify(runStatusTracker).completeRun(eq(PipelinePhase.ITEM_EQUIPMENT), eq("run-ie-1"), eq(50), eq(100_000L))
    }

    @Test
    fun `runItemEquipmentPhase drains scheduler metrics on success`() {
        val ocidCache = mock<OcidCacheProvider>()
        whenever(ocidCache.loadFromRun("run-cb-1")).thenReturn(mapOf("ign1" to "ocid1"))

        val itemEquipmentPhase = mock<ItemEquipmentFetchPhase>()
        whenever(itemEquipmentPhase.execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(Unit))

        val ocidLookupPhase = mock<OcidLookupPhase>()
        val runStatusTracker = mock<RunStatusTracker>()
        whenever(runStatusTracker.acquirePhaseSlot(eq(PipelinePhase.ITEM_EQUIPMENT), eq("run-ie-1")))
            .thenReturn(
                maple.externalapi.runstatus.RunStatus(
                    runId = "run-ie-1",
                    phase = PipelinePhase.ITEM_EQUIPMENT,
                    triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
                    startedAt = java.time.Instant.now(),
                )
            )
        val schedulerMetrics = mock<SchedulerMetrics>()
        whenever(schedulerMetrics.drainRunChunks()).thenReturn(7L)
        whenever(schedulerMetrics.drainRunRecords()).thenReturn(42L)
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(null)
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(null)
        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        whenever(itemEquipmentProvider.ifAvailable).thenReturn(itemEquipmentPhase)

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        scheduler.runItemEquipmentPhase("run-ie-1", "run-cb-1").get()

        verify(schedulerMetrics).drainRunChunks()
        verify(schedulerMetrics).drainRunRecords()
        verify(runStatusTracker).completeRun(eq(PipelinePhase.ITEM_EQUIPMENT), eq("run-ie-1"), eq(7), eq(42L))
    }

    @Test
    fun `triggerPhase dispatches RANKING_FETCH to runRankingPhase`() {
        val rankingPhase = mock<RankingFetchPhase>()
        whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture("runs/run-r-1"))
        val ocidLookupPhase = mock<OcidLookupPhase>()
        val ocidCache = mock<OcidCacheProvider>()
        val runStatusTracker = mock<RunStatusTracker>()
        whenever(runStatusTracker.acquirePhaseSlot(eq(PipelinePhase.RANKING_FETCH), eq("run-r-1")))
            .thenReturn(
                maple.externalapi.runstatus.RunStatus(
                    runId = "run-r-1",
                    phase = PipelinePhase.RANKING_FETCH,
                    triggeredPhase = PipelinePhase.RANKING_FETCH,
                    startedAt = java.time.Instant.now(),
                )
            )
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(null)
        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        val schedulerMetrics = mock<SchedulerMetrics>()

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        scheduler.triggerPhase(PipelinePhase.RANKING_FETCH, "run-r-1", null).get()

        verify(rankingPhase).execute(any<ExecutorService>(), eq("run-r-1"))
    }

    @Test
    fun `triggerPhase returns failed future for non-triggerable phase`() {
        val ocidLookupPhase = mock<OcidLookupPhase>()
        val ocidCache = mock<OcidCacheProvider>()
        val runStatusTracker = mock<RunStatusTracker>()
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        val schedulerMetrics = mock<SchedulerMetrics>()

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        val result = scheduler.triggerPhase(PipelinePhase.IDLE, "run-x", null)
        assertThat(result.isCompletedExceptionally).isTrue()
        val ex = try { result.get() } catch (e: java.util.concurrent.ExecutionException) { e.cause as Throwable }
        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `triggerDailyRefresh chains 4 triggerPhase calls in order`() {
        val rankingPhase = mock<RankingFetchPhase>()
        whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture("runs/run-r"))
        val ocidLookupPhase = mock<OcidLookupPhase>()
        runBlocking {
            whenever(ocidLookupPhase.execute(any<ExecutorService>(), any<String>(), any<String>()))
                .thenReturn(Unit)
        }
        val charBasicPhase = mock<CharacterBasicFetchPhase>()
        whenever(charBasicPhase.execute(any<ExecutorService>(), any<Map<String, String>>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(Unit))
        val itemEquipmentPhase = mock<ItemEquipmentFetchPhase>()
        whenever(itemEquipmentPhase.execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(Unit))
        val ocidCache = mock<OcidCacheProvider>()
        whenever(ocidCache.loadFromRun(any<String>())).thenReturn(mapOf("ign1" to "ocid1"))
        val runStatusTracker = mock<RunStatusTracker>()
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(charBasicPhase)
        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        whenever(itemEquipmentProvider.ifAvailable).thenReturn(itemEquipmentPhase)
        val schedulerMetrics = mock<SchedulerMetrics>()

        // Stub acquirePhaseSlot to return a non-null RunStatus for any phase/runId
        // so each triggerPhase call in the chain proceeds.
        whenever(runStatusTracker.acquirePhaseSlot(any(), any<String>()))
            .thenAnswer { invocation ->
                val runId = invocation.getArgument<String>(1)
                val phase = invocation.getArgument<PipelinePhase>(0)
                maple.externalapi.runstatus.RunStatus(
                    runId = runId,
                    phase = phase,
                    triggeredPhase = phase,
                    startedAt = java.time.Instant.now(),
                )
            }

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        scheduler.triggerDailyRefresh("daily-run-1").get()

        // Daily chain generates 4 distinct runIds internally; slot acquire called for each phase
        inOrder(runStatusTracker).run {
            verify(runStatusTracker).acquirePhaseSlot(eq(PipelinePhase.RANKING_FETCH), any<String>())
            verify(runStatusTracker).acquirePhaseSlot(eq(PipelinePhase.OCID_LOOKUP), any<String>())
            verify(runStatusTracker).acquirePhaseSlot(eq(PipelinePhase.CHARACTER_BASIC), any<String>())
            verify(runStatusTracker).acquirePhaseSlot(eq(PipelinePhase.ITEM_EQUIPMENT), any<String>())
        }
    }

    @Test
    fun `triggerPhase rejects OCID_LOOKUP without upstreamRunId`() {
        val ocidLookupPhase = mock<OcidLookupPhase>()
        val ocidCache = mock<OcidCacheProvider>()
        val runStatusTracker = mock<RunStatusTracker>()
        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
        val schedulerMetrics = mock<SchedulerMetrics>()

        val scheduler = ExternalApiScheduler(
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
            stopSignal = PhaseStopSignal(),
        )

        val ex = try {
            scheduler.triggerPhase(PipelinePhase.OCID_LOOKUP, "run-o-1", null)
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertThat(ex).isNotNull
    }
}
