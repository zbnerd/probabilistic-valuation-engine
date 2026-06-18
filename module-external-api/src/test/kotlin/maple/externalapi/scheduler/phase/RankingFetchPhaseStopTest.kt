package maple.externalapi.scheduler.phase

import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.PhaseStoppedException
import maple.externalapi.runstatus.PipelinePhase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.mockito.kotlin.mock
import java.util.concurrent.Executors

class RankingFetchPhaseStopTest {

    @Test
    fun `execute throws PhaseStoppedException when stop requested before first page`() {
        val signal = PhaseStopSignal()
        signal.requestStop(PipelinePhase.RANKING_FETCH)

        val phase = RankingFetchPhase(
            clientPort = mock(),
            objectMapper = com.fasterxml.jackson.databind.ObjectMapper(),
            chunkingProperties = mock(),
            volumeMetrics = mock(),
            metrics = mock(),
            rankingPublisher = mock(),
            maxPages = 5,
            permitsPerSecond = 100,
            runMarkerWriter = mock(),
            objectStorage = mock(),
            stopSignal = signal,
        )

        val ex = assertThrows(PhaseStoppedException::class.java) {
            phase.execute(Executors.newSingleThreadExecutor(), "test-run").join()
        }
        assertEquals(PipelinePhase.RANKING_FETCH, ex.phase)
    }
}
