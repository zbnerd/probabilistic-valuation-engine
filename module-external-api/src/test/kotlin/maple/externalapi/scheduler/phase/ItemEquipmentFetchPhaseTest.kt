package maple.externalapi.scheduler.phase

import java.time.Clock
import java.time.Duration
import java.util.AbstractMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.snapshot.EndpointSinkFactory
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.pipeline.artifact.lifecycle.RunLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ItemEquipmentFetchPhaseTest {
    @Test
    fun `execute writes exact endpoint marker before creating sink`() {
        val objectStorage = mock<ObjectStorage>()
        whenever(objectStorage.put(any(), any<ByteArray>())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val bytes = invocation.getArgument<ByteArray>(1)
            PutResult(key, bytes.size.toLong(), null)
        }
        val sinkFactory = mock<EndpointSinkFactory>()
        val phase = phase(objectStorage, sinkFactory)
        val executor = Executors.newSingleThreadExecutor()

        try {
            awaitFuture(phase.execute(executor, entries(), RUN_ID))
        } finally {
            executor.shutdownNow()
        }

        verify(objectStorage).put(eq("runs/run-item/item-equipment/_RUNNING"), any<ByteArray>())
        verify(sinkFactory).createForItemEquipment(RUN_ID)
    }

    @Test
    fun `marker failure prevents item equipment sink creation`() {
        val objectStorage = mock<ObjectStorage>()
        whenever(objectStorage.put(any(), any<ByteArray>())).thenThrow(IllegalStateException("marker failed"))
        val sinkFactory = mock<EndpointSinkFactory>()
        val phase = phase(objectStorage, sinkFactory)
        val executor = Executors.newSingleThreadExecutor()

        val outcome = try {
            awaitFuture(phase.execute(executor, entries(), RUN_ID))
        } finally {
            executor.shutdownNow()
        }

        assertThat(outcome.failure).hasRootCauseMessage("marker failed")
        verify(sinkFactory, never()).createForItemEquipment(any())
    }

    private fun phase(objectStorage: ObjectStorage, sinkFactory: EndpointSinkFactory): ItemEquipmentFetchPhase =
        ItemEquipmentFetchPhase(
            objectStorage = objectStorage,
            chunkingProperties = SnapshotChunkingProperties(),
            metrics = mock<ExternalApiMetrics>(),
            fetchMetrics = mock<SnapshotFetchMetrics>(),
            batchSupport = mock<BatchFetchSupport>(),
            sinkFactory = sinkFactory,
            permitsPerSecond = 1000,
            batchSize = 10,
            clock = Clock.systemUTC(),
            runIdGenerator = RunIdGenerator(Clock.systemUTC()),
            runLifecycle = RunLifecycle(objectStorage, java.util.concurrent.Executor(Runnable::run)),
            schedulerProgressLogger = mock<SchedulerProgressLogger>(),
        )

    private fun entries(): List<Map.Entry<String, String>> = listOf(AbstractMap.SimpleEntry("ign", "ocid"))

    private fun <T> awaitFuture(future: CompletableFuture<T>): FutureOutcome<T> {
        val captured = AtomicReference<FutureOutcome<T>>()
        future.whenComplete { value, failure -> captured.set(FutureOutcome(value, failure)) }
        await().atMost(Duration.ofSeconds(5)).until { captured.get() != null }
        return requireNotNull(captured.get())
    }

    private data class FutureOutcome<T>(val value: T?, val failure: Throwable?)

    private companion object {
        const val RUN_ID: String = "run-item"
    }
}
