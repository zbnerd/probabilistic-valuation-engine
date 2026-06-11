package maple.externalapi.scheduler.phase

import maple.expectation.common.storage.ObjectStorage
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.snapshot.EndpointSinkFactory
import maple.externalapi.snapshot.SnapshotChunkingProperties
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Migration Task 9: `execute(...)` must call `runMarkerWriter.writeRunMarker`
 * with a key starting with `runs/<runId>/item-equipment`.
 */
class ItemEquipmentFetchPhaseTest {

    @Test
    fun `execute writes running marker with runs slash prefix when entries are non-empty`() {
        val objectStorage = mock<ObjectStorage>()

        val batchSupport = mock<BatchFetchSupport>()
        whenever(batchSupport.newRateLimiter(any()))
            .thenReturn(io.github.bucket4j.Bucket.builder()
                .addLimit(io.github.bucket4j.Bandwidth.builder()
                    .capacity(1_000_000)
                    .refillIntervally(1_000_000, java.time.Duration.ofSeconds(1))
                    .build())
                .build())

        val runMarkerWriter = mock<RunMarkerWriter>()
        val keyCaptor = argumentCaptor<String>()

        val externalApiMetrics = mock<ExternalApiMetrics>()

        val phase = ItemEquipmentFetchPhase(
            objectStorage = objectStorage,
            chunkingProperties = SnapshotChunkingProperties(),
            metrics = externalApiMetrics,
            fetchMetrics = mock<SnapshotFetchMetrics>(),
            batchSupport = batchSupport,
            sinkFactory = mock<EndpointSinkFactory>(),
            permitsPerSecond = 1000,
            batchSize = 10,
            clock = Clock.systemUTC(),
            runIdGenerator = RunIdGenerator(Clock.systemUTC()),
            runMarkerWriter = runMarkerWriter,
            schedulerProgressLogger = mock<SchedulerProgressLogger>(),
        )

        val executor = Executors.newSingleThreadExecutor()
        val entries = listOf(java.util.AbstractMap.SimpleEntry("ign", "ocid"))
        try {
            // We don't need the full batch to run — the marker is written
            // before the batch loop. Allow the rest to fail at the mocked
            // sinkFactory; the marker write is what we assert.
            runCatching { phase.execute(executor, entries).get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        verify(runMarkerWriter).writeRunMarker(keyCaptor.capture())
        assertTrue(
            keyCaptor.firstValue.startsWith("runs/"),
            "expected runKey to start with 'runs/' but was '${keyCaptor.firstValue}'",
        )
        assertTrue(
            keyCaptor.firstValue.endsWith("/item-equipment"),
            "expected runKey to end with '/item-equipment' but was '${keyCaptor.firstValue}'",
        )
    }
}
