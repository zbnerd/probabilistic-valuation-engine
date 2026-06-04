package maple.externalapi.scheduler

import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import maple.externalapi.scheduler.phase.SnapshotFetchPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExternalApiSchedulerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var ocidLookupPhase: OcidLookupPhase
    private lateinit var snapshotFetchPhase: SnapshotFetchPhase
    private lateinit var ocidCacheProvider: OcidCacheProvider
    private lateinit var rankingPhaseProvider: ObjectProvider<RankingFetchPhase>
    private lateinit var rankingPhase: RankingFetchPhase
    private lateinit var tracker: RunStatusTracker
    private lateinit var executor: ExecutorService
    private lateinit var scheduler: ExternalApiScheduler

    @BeforeEach
    fun setUp() {
        ocidLookupPhase = mock()
        snapshotFetchPhase = mock()
        ocidCacheProvider = mock()
        rankingPhase = mock()
        rankingPhaseProvider = mock()

        whenever(rankingPhaseProvider.ifAvailable).thenReturn(rankingPhase)
        whenever(ocidCacheProvider.refresh()).thenReturn(emptyMap())

        tracker = RunStatusTracker()
        executor = Executors.newSingleThreadExecutor()
        scheduler = ExternalApiScheduler(
            ocidLookupPhase = ocidLookupPhase,
            snapshotFetchPhase = snapshotFetchPhase,
            ocidCacheProvider = ocidCacheProvider,
            rankingFetchPhaseProvider = rankingPhaseProvider,
            runStatusTracker = tracker,
            scheduleEnabled = false,
            runOnStartup = false,
            skipCharacterBasic = false,
            executor = executor,
        )
    }

    @AfterEach
    fun tearDown() {
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    @Test
    fun `ranking failure does not invoke OCID phase and records FAILED`() {
        whenever(rankingPhase.execute(executor))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("nexon 503")))

        scheduler.triggerDailyRefresh("run-fail-1")
        awaitChain()

        verify(ocidLookupPhase, never()).execute(any(), any())
        verify(snapshotFetchPhase, never()).executeCharacterBasic(any(), any())

        val last = tracker.getLastCompletedRun()
        assertThat(last).isNotNull
        assertThat(last!!.phase).isEqualTo(PipelinePhase.FAILED)
        assertThat(last.errorMessage).contains("nexon 503")
        assertThat(scheduler.itemEquipmentStarted.get()).isFalse()
    }

    @Test
    fun `ranking returns null runDir is treated as failure`() {
        whenever(rankingPhase.execute(executor))
            .thenReturn(CompletableFuture.completedFuture(null))

        scheduler.triggerDailyRefresh("run-null-rundir")
        awaitChain()

        verify(ocidLookupPhase, never()).execute(any(), any())
        verify(snapshotFetchPhase, never()).executeCharacterBasic(any(), any())

        val last = tracker.getLastCompletedRun()
        assertThat(last).isNotNull
        assertThat(last!!.phase).isEqualTo(PipelinePhase.FAILED)
        assertThat(last.errorMessage).contains("ranking fetch returned null runDir")
        assertThat(scheduler.itemEquipmentStarted.get()).isFalse()
    }

    @Test
    fun `happy path records COMPLETED and starts item-equipment loop`() {
        val runDir = tempDir.resolve("runs/run-ok")
        whenever(rankingPhase.execute(executor))
            .thenReturn(CompletableFuture.completedFuture(runDir))
        whenever(ocidLookupPhase.execute(executor, runDir))
            .thenReturn(CompletableFuture.completedFuture(runDir))
        whenever(snapshotFetchPhase.executeCharacterBasic(executor, emptyMap()))
            .thenReturn(CompletableFuture.completedFuture(Unit))

        scheduler.triggerDailyRefresh("run-ok")
        awaitChain()

        verify(ocidLookupPhase).execute(executor, runDir)
        verify(snapshotFetchPhase).executeCharacterBasic(executor, emptyMap())

        val last = tracker.getLastCompletedRun()
        assertThat(last).isNotNull
        assertThat(last!!.phase).isEqualTo(PipelinePhase.COMPLETED)
        assertThat(scheduler.itemEquipmentStarted.get()).isTrue()
    }

    private fun awaitChain() {
        // Wait for the whenComplete branch to finish. We poll the tracker because
        // ExternalApiScheduler exposes no callback for chain completion and
        // Thread.sleep is forbidden by testing-conventions.md.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (tracker.getLastCompletedRun() != null) return
            Thread.sleep(20)
        }
        throw AssertionError("scheduler chain did not complete within 2s")
    }
}
