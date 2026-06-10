package maple.externalapi.scheduler.phase

import maple.expectation.common.storage.ObjectStorage
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.snapshot.EndpointSinkFactory
import maple.externalapi.snapshot.SnapshotChunkingProperties
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Migration Task 9: `execute(...)` must call `runMarkerWriter.writeRunMarker`
 * with a key starting with `runs/<runId>/character-basic`.
 */
class CharacterBasicFetchPhaseTest {

    @Test
    fun `execute writes running marker with runs slash prefix when no existing keys`() {
        val artifactStore = mock<ExternalApiArtifactStorePort>()
        whenever(artifactStore.listStoredKeys(ExternalApiEndpoint.CHARACTER_BASIC))
            .thenReturn(emptyList())

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
        // writeRunMarker returns Unit; no stub needed beyond capture

        val externalApiMetrics = mock<ExternalApiMetrics>()

        val phase = CharacterBasicFetchPhase(
            artifactStore = artifactStore,
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
        val ocidCache = mapOf("ign" to "ocid")
        try {
            // Just invoke the marker write + skip path branch — we don't need the
            // full batch to run. The listStoredKeys is empty, so the skip
            // branch is not taken. To make the test deterministic without
            // driving the full batch, we let it fail at the sinkFactory call
            // (which is mocked) and assert the marker was written first.
            runCatching { phase.execute(executor, ocidCache).get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        org.mockito.kotlin.verify(runMarkerWriter).writeRunMarker(keyCaptor.capture())
        assertTrue(
            keyCaptor.firstValue.startsWith("runs/"),
            "expected runKey to start with 'runs/' but was '${keyCaptor.firstValue}'",
        )
        assertTrue(
            keyCaptor.firstValue.endsWith("/character-basic"),
            "expected runKey to end with '/character-basic' but was '${keyCaptor.firstValue}'",
        )
    }
}
