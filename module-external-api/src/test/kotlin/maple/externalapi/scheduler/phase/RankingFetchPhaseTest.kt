package maple.externalapi.scheduler.phase

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.common.storage.PutResult
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.snapshot.ChunkedSnapshotSink
import maple.externalapi.snapshot.EndpointSinkFactory
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.lifecycle.RunLifecycle
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RankingFetchPhaseTest {
    @Test
    fun `execute starts legacy ranking marker before sink creation and returns typed run root`() {
        val storage = markerStorage()
        val clientPort = mock<ExternalApiClientPort>()
        whenever(clientPort.fetch(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture("{}".toByteArray()))
        val sink = mock<ChunkedSnapshotSink>()
        whenever(sink.closeAsync()).thenReturn(CompletableFuture.completedFuture(null))
        val sinkFactory = mock<EndpointSinkFactory>()
        whenever(sinkFactory.createForRanking(RUN_ID)).thenReturn(sink)
        val phase = phase(storage, clientPort, sinkFactory)
        val workerExecutor = Executors.newSingleThreadExecutor()

        val outcome = try {
            awaitFuture(phase.execute(workerExecutor, RUN_ID))
        } finally {
            workerExecutor.shutdownNow()
        }

        assertThat(outcome.failure).isNull()
        assertThat(outcome.value).isEqualTo(SourceArtifactLayout.runRoot(RUN_ID).value)
        inOrder(storage, sinkFactory, clientPort).run {
            verify(storage).put(
                org.mockito.kotlin.eq("runs/20260610-xyz/_RUNNING"),
                any<ByteArray>(),
            )
            verify(sinkFactory).createForRanking(RUN_ID)
            verify(clientPort).fetch(any(), any(), any())
        }
        verify(storage, never()).put(
            org.mockito.kotlin.eq("runs/20260610-xyz/ranking-overall/_RUNNING"),
            any<ByteArray>(),
        )
    }

    @Test
    fun `marker write failure prevents ranking sink creation and fetch submission`() {
        val storage = markerStorage(failure = IllegalStateException("marker unavailable"))
        val clientPort = mock<ExternalApiClientPort>()
        val sinkFactory = mock<EndpointSinkFactory>()
        val phase = phase(storage, clientPort, sinkFactory)
        val workerExecutor = Executors.newSingleThreadExecutor()

        val outcome = try {
            awaitFuture(phase.execute(workerExecutor, RUN_ID))
        } finally {
            workerExecutor.shutdownNow()
        }

        assertThat(outcome.failure).hasRootCauseMessage("marker unavailable")
        verify(sinkFactory, never()).createForRanking(any())
        verify(clientPort, never()).fetch(any(), any(), any())
    }

    private fun phase(
        storage: ConditionalObjectStorage,
        clientPort: ExternalApiClientPort,
        sinkFactory: EndpointSinkFactory,
    ): RankingFetchPhase = RankingFetchPhase(
        clientPort = clientPort,
        objectMapper = com.fasterxml.jackson.databind.ObjectMapper(),
        chunkingProperties = SnapshotChunkingProperties(),
        metrics = mock<ExternalApiMetrics>(),
        maxPages = 1,
        permitsPerSecond = 1000,
        runLifecycle = RunLifecycle(storage, java.util.concurrent.Executor(Runnable::run)),
        sinkFactory = sinkFactory,
        stopSignal = PhaseStopSignal(),
    )

    private fun markerStorage(failure: Throwable? = null): ConditionalObjectStorage {
        val storage = mock<ConditionalObjectStorage>()
        whenever(storage.put(any(), any<ByteArray>())).thenAnswer { invocation ->
            if (failure != null) throw failure
            val key = invocation.getArgument<String>(0)
            val bytes = invocation.getArgument<ByteArray>(1)
            PutResult(key, bytes.size.toLong(), null)
        }
        return storage
    }

    private fun <T> awaitFuture(future: CompletableFuture<T>): FutureOutcome<T> {
        val captured = AtomicReference<FutureOutcome<T>>()
        future.whenComplete { value, failure -> captured.set(FutureOutcome(value, failure)) }
        await().atMost(Duration.ofSeconds(5)).until { captured.get() != null }
        return requireNotNull(captured.get())
    }

    private data class FutureOutcome<T>(val value: T?, val failure: Throwable?)

    private companion object {
        const val RUN_ID: String = "20260610-xyz"
    }
}
