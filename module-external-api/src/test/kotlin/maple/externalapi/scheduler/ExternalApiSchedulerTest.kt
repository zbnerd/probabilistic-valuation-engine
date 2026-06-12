package maple.externalapi.scheduler

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.runBlocking
import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.phase.CharacterBasicFetchPhase
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider

/**
 * Migration Task 10: [ExternalApiScheduler] must treat the result of
 * [RankingFetchPhase.execute] as a runKey String (e.g. "runs/20260610-xyz"),
 * strip the "runs/" prefix to obtain the runId, and pass the full runKey
 * (not the runId) downstream to [OcidLookupPhase.execute].
 *
 * Run-status wiring: when char-basic ends, ExternalApiScheduler must transition
 * to [PipelinePhase.CHARACTER_BASIC_DONE] — NOT [PipelinePhase.COMPLETED] — because
 * item-equipment runs in a SEPARATE continuous loop
 * ([ItemEquipmentContinuousLoop]) and signals full completion from there.
 */
class ExternalApiSchedulerTest {

    @Test
    fun `triggerDailyRefresh extracts runId from runKey and forwards runKey to OCID lookup phase`() {
        val rankingPhase = mock<RankingFetchPhase>()
        val runKey = "runs/20260610-xyz"
        whenever(rankingPhase.execute(any<ExecutorService>()))
            .thenReturn(CompletableFuture.completedFuture(runKey))

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
        assertThat(runKeyCaptor.firstValue).isEqualTo("runs/20260610-xyz")

        // The runId passed to startRun is the segment after "runs/".
        verify(runStatusTracker, timeout(5_000)).startRun("20260610-xyz")
    }

    @Test
    fun `triggerDailyRefresh transitions to CHARACTER_BASIC_DONE not COMPLETED after char-basic`() {
        // Regression test for the bug where /api/internal/run-status showed
        // terminal=true while ITEM_EQUIPMENT was still running in
        // ItemEquipmentContinuousLoop. The fix is: ExternalApiScheduler signals
        // char-basic end via CHARACTER_BASIC_DONE; the continuous loop signals
        // full completion via completeRun.

        val rankingPhase = mock<RankingFetchPhase>()
        whenever(rankingPhase.execute(any<ExecutorService>()))
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
}
