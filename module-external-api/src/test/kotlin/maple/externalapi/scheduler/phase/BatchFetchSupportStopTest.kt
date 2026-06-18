package maple.externalapi.scheduler.phase

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import java.time.Duration
import java.time.Instant
import java.util.AbstractMap
import java.util.concurrent.CompletableFuture
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.PhaseStoppedException
import maple.externalapi.snapshot.ChunkedSnapshotSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class BatchFetchSupportStopTest {

    @Test
    fun `processBatch throws PhaseStoppedException when stop requested before first batch`() {
        val clientPort = mock<ExternalApiClientPort>()
        whenever(clientPort.fetch(any(), any(), any())).thenAnswer {
            CompletableFuture.completedFuture(ByteArray(0))
        }
        val signal = PhaseStopSignal()
        signal.requestStop(PipelinePhase.ITEM_EQUIPMENT)

        val support = BatchFetchSupport(
            clientPort = clientPort,
            fetchMetrics = mock<SnapshotFetchMetrics>(),
            maxInFlight = 10,
            schedulerRateLimiter = mock(),
            schedulerProgressLogger = mock(),
            httpStatusExtractor = mock(),
            stopSignal = signal,
        )

        val ctx = BatchFetchContext(
            endpoint = "item-equipment",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            apiEndpoint = ExternalApiEndpoint.ITEM_EQUIPMENT,
            onFetched = {},
            onFailed = {},
        )
        val sink = mock<ChunkedSnapshotSink>()

        val ex = assertThrows(PhaseStoppedException::class.java) {
            kotlinx.coroutines.runBlocking {
                support.processBatch(
                    rateLimiter = Bucket.builder()
                        .addLimit(Bandwidth.simple(100L, Duration.ofSeconds(1)))
                        .build(),
                    entries = listOf(
                        AbstractMap.SimpleEntry("ign1", "ocid1"),
                        AbstractMap.SimpleEntry("ign2", "ocid2"),
                    ),
                    batchSize = 10,
                    ctx = ctx,
                    sink = sink,
                    runId = "test-run",
                    start = Instant.now(),
                )
            }
        }
        assertEquals(PipelinePhase.ITEM_EQUIPMENT, ex.phase)
    }
}
