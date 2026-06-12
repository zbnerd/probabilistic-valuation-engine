package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import maple.expectation.common.storage.ObjectStorage
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.SinkEventPublisher
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.SnapshotSinkEventPublisher
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import maple.expectation.common.storage.PutResult
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.InputStream
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Migration Task 8: `execute(workerExecutor)` must return `CompletableFuture<String>`
 * whose value is the runKey (e.g. `runs/20260610-...`) — not a Path.
 */
class RankingFetchPhaseTest {

    @Test
    fun `execute returns runKey as String starting with runs slash`() {
        val storage = mock<ObjectStorage>()
        // RankingFetchPhase writes a single empty chunk (empty ranking array) at
        // close via the new GzipJsonlChunkWriter → putStream path. Mock it so
        // close() returns a non-null PutResult.
        whenever(storage.putStream(any<String>(), any<InputStream>()))
            .thenAnswer { invocation ->
                val key: String = invocation.getArgument(0)
                val input: InputStream = invocation.getArgument(1)
                PutResult(key, input.readBytes().size.toLong(), null)
            }
        whenever(storage.put(any<String>(), any<ByteArray>()))
            .thenAnswer { invocation ->
                val key: String = invocation.getArgument(0)
                val bytes: ByteArray = invocation.getArgument(1)
                PutResult(key, bytes.size.toLong(), null)
            }

        val objectMapper = ObjectMapper()
            .registerModule(kotlinModule())
            .registerModule(JavaTimeModule())

        // Ranking API returns an empty ranking array — phase finishes after one page.
        val clientPort = mock<ExternalApiClientPort>()
        whenever(clientPort.fetch(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture("{}".toByteArray()))

        // Mock event publisher — we only care about the runKey return value.
        val eventPublisher = mock<SnapshotChunkEventPublisher>()
        whenever(eventPublisher.publishChunkReady(any<SnapshotChunkReadyEvent>()))
            .thenReturn(CompletableFuture.completedFuture(null))
        whenever(eventPublisher.publishRunCompleted(any<SnapshotRunCompletedEvent>()))
            .thenReturn(CompletableFuture.completedFuture(null))
        whenever(eventPublisher.publishRunFailed(any<SnapshotRunFailedEvent>()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val volumeMetrics = mock<SnapshotVolumeMetrics>()
        val externalApiMetrics = mock<ExternalApiMetrics>()

        val chunkingProperties = SnapshotChunkingProperties()

        val phase = RankingFetchPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            chunkingProperties = chunkingProperties,
            volumeMetrics = volumeMetrics,
            metrics = externalApiMetrics,
            rankingPublisher = eventPublisher,
            maxPages = 1,
            permitsPerSecond = 1000,
            runMarkerWriter = RunMarkerWriter(Clock.systemUTC(), storage),
            objectStorage = storage,
        )

        val result: CompletableFuture<String> = phase.execute(Executors.newSingleThreadExecutor())
        val runKey = result.get(15, TimeUnit.SECONDS)

        assertTrue(
            runKey.startsWith("runs/"),
            "expected runKey to start with 'runs/' but was '$runKey'",
        )
    }
}
