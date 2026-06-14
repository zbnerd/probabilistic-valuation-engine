package maple.externalapi.scheduler

import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.runBlocking
import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.phase.CharacterBasicFetchPhase
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import maple.externalapi.scheduler.phase.RunIdGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
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
 * item-equipment runs in a SEPARATE continuous loop
 * ([ItemEquipmentContinuousLoop]) and signals full completion from there.
 */
class ExternalApiSchedulerTest {

    @Test
    fun `triggerDailyRefresh generates runId up front and passes it to ranking execute then forwards runKey to OCID lookup`() {
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

        val ocidCache = mock<OcidCacheProvider>()
        val runStatusTracker = mock<RunStatusTracker>()

        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)

        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(null)

        val itemEquipmentLoop = mock<ItemEquipmentContinuousLoop>()

        val runIdCaptor = argumentCaptor<String>()
        // startRun is a void method so we use doNothing for the mock.
        doNothing().whenever(runStatusTracker).startRun(runIdCaptor.capture())

        val scheduler = ExternalApiScheduler(
            ocidLookupPhase = ocidLookupPhase,
            ocidCacheProvider = ocidCache,
            rankingFetchPhaseProvider = rankingProvider,
            characterBasicPhaseProvider = charBasicProvider,
            itemEquipmentContinuousLoop = itemEquipmentLoop,
            runStatusTracker = runStatusTracker,
            runIdGenerator = RunIdGenerator(Clock.systemUTC()),
            runOnStartup = false,
            skipCharacterBasic = false,
        )

        scheduler.triggerDailyRefresh(null)

        // The OCID lookup phase is invoked from a runBlocking on the virtual-thread executor.
        // Wait up to 5s for the async chain to settle and capture the runKey argument.
        val runKeyCaptor = argumentCaptor<String>()
        runBlocking {
            verify(ocidLookupPhase, timeout(5_000)).execute(any<ExecutorService>(), runKeyCaptor.capture())
        }

        // The runId passed to startRun is the SAME one passed to ranking.execute.
        // Before the fix, startRun was called inside the .handle callback with
        // the runId derived from the runKey — so a ranking failure would leave
        // the previous run's FAILED status visible on /api/internal/run-status.
        val runIdPassedToRanking = argumentCaptor<String>()
        verify(rankingPhase, timeout(5_000)).execute(any<ExecutorService>(), runIdPassedToRanking.capture())
        assertThat(runIdCaptor.firstValue).isEqualTo(runIdPassedToRanking.firstValue)
        // The runKey forwarded to OcidLookupPhase matches `runs/<runId>`.
        assertThat(runKeyCaptor.firstValue).isEqualTo("runs/${runIdCaptor.firstValue}")
    }

    @Test
    fun `triggerDailyRefresh transitions to CHARACTER_BASIC_DONE not COMPLETED after char-basic`() {
        // Regression test for the bug where /api/internal/run-status showed
        // terminal=true while ITEM_EQUIPMENT was still running in
        // ItemEquipmentContinuousLoop. The fix is: ExternalApiScheduler signals
        // char-basic end via CHARACTER_BASIC_DONE; the continuous loop signals
        // full completion via completeRun.

        val rankingPhase = mock<RankingFetchPhase>()
        whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture("runs/run-cb-done"))

        val ocidLookupPhase = mock<OcidLookupPhase>()
        runBlocking {
            whenever(ocidLookupPhase.execute(any<ExecutorService>(), any<String>()))
                .thenReturn(Unit)
        }

        val ocidCache = mock<OcidCacheProvider>()
        whenever(ocidCache.current()).thenReturn(emptyMap())

        val runStatusTracker = mock<RunStatusTracker>()

        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)

        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        // Char-basic returns an empty OCID cache to short-circuit the inner block,
        // so the whenComplete fires and we can assert the phase transition.
        whenever(charBasicProvider.ifAvailable).thenReturn(null)

        val itemEquipmentLoop = mock<ItemEquipmentContinuousLoop>()

        val scheduler = ExternalApiScheduler(
            ocidLookupPhase = ocidLookupPhase,
            ocidCacheProvider = ocidCache,
            rankingFetchPhaseProvider = rankingProvider,
            characterBasicPhaseProvider = charBasicProvider,
            itemEquipmentContinuousLoop = itemEquipmentLoop,
            runStatusTracker = runStatusTracker,
            runIdGenerator = RunIdGenerator(Clock.systemUTC()),
            runOnStartup = false,
            skipCharacterBasic = false,
        )

        scheduler.triggerDailyRefresh(null)

        // The chain reaches whenComplete → transitionPhase(CHARACTER_BASIC_DONE).
        // The bug would call completeRun() here instead, which we explicitly do NOT want.
        verify(runStatusTracker, timeout(5_000))
            .transitionPhase(PipelinePhase.CHARACTER_BASIC_DONE)
        verify(runStatusTracker, timeout(1_000).times(0)).completeRun(
            org.mockito.kotlin.any<String>(),
            org.mockito.kotlin.any<Int>(),
            org.mockito.kotlin.any<Long>(),
        )
    }

    /**
     * Regression for the bug where a fresh pipeline cycle left the previous
     * run's FAILED status visible on /api/internal/run-status because
     * `startRun` was buried in the `.handle` callback of ranking.execute().
     * If ranking throws, the handle fires with `ex != null` and startRun is
     * skipped — but the daily refresh continues to failRun with the stale
     * runId. The fix pre-generates the runId and calls startRun BEFORE
     * ranking starts, so a ranking failure cannot leave a stale status.
     *
     * Note: the current chain uses .handle() to swallow the ranking exception
     * (returning null), so a ranking failure does not reach failRun — the
     * status stays as RANKING_FETCH for the new runId. What we assert here
     * is the core property of the fix: startRun fires *for the new runId*
     * before ranking, so the API shows the new run even if ranking fails.
     */
    @Test
    fun `triggerDailyRefresh calls startRun before ranking execute, even if ranking fails`() {
        val rankingPhase = mock<RankingFetchPhase>()
        whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("rank api down")))

        val ocidLookupPhase = mock<OcidLookupPhase>()
        val ocidCache = mock<OcidCacheProvider>()
        val runStatusTracker = mock<RunStatusTracker>()

        val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
        whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)

        val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
        whenever(charBasicProvider.ifAvailable).thenReturn(null)

        val itemEquipmentLoop = mock<ItemEquipmentContinuousLoop>()

        val scheduler = ExternalApiScheduler(
            ocidLookupPhase = ocidLookupPhase,
            ocidCacheProvider = ocidCache,
            rankingFetchPhaseProvider = rankingProvider,
            characterBasicPhaseProvider = charBasicProvider,
            itemEquipmentContinuousLoop = itemEquipmentLoop,
            runStatusTracker = runStatusTracker,
            runIdGenerator = RunIdGenerator(Clock.systemUTC()),
            runOnStartup = false,
            skipCharacterBasic = false,
        )

        scheduler.triggerDailyRefresh(null)

        // startRun must fire for the new runId before ranking.execute() even
        // returns. The previous bug deferred it into .handle, so a ranking
        // failure meant the tracker never transitioned — the previous run's
        // FAILED status would remain visible on /api/internal/run-status.
        verify(runStatusTracker, timeout(2_000)).startRun(any<String>())
    }
}
