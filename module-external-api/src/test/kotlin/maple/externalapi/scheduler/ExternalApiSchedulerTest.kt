package maple.externalapi.scheduler

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.runBlocking
import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.metrics.SchedulerMetrics
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.phase.CharacterBasicFetchPhase
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
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
        val schedulerMetrics = mock<SchedulerMetrics>()
        whenever(schedulerMetrics.drainRunChunks()).thenReturn(0L)
        whenever(schedulerMetrics.drainRunRecords()).thenReturn(0L)

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
            schedulerMetrics = schedulerMetrics,
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
}
