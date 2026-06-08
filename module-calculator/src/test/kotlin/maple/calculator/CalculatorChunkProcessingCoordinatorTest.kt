package maple.calculator

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import maple.calculator.event.ChunkProcessingEvent
import maple.calculator.event.KafkaResultEventPublisher
import maple.calculator.metrics.CalculatorMetricsListener
import maple.calculator.model.ChunkResult
import maple.calculator.processor.SnapshotChunkProcessor
import maple.calculator.storage.ObjectStorage
import maple.expectation.common.event.SnapshotChunkReadyEvent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.check
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class CalculatorChunkProcessingCoordinatorTest {

    @Mock
    private lateinit var chunkProcessor: SnapshotChunkProcessor

    @Mock
    private lateinit var resultEventPublisher: KafkaResultEventPublisher

    @Mock
    private lateinit var objectStorage: ObjectStorage

    @Mock
    private lateinit var metricsListener: CalculatorMetricsListener

    private lateinit var coordinator: CalculatorChunkProcessingCoordinator

    @BeforeEach
    fun setUp() {
        coordinator = CalculatorChunkProcessingCoordinator(
            chunkProcessor = chunkProcessor,
            resultEventPublisher = resultEventPublisher,
            objectStorage = objectStorage,
            metricsListener = metricsListener,
            vtDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun testEvent(
        endpoint: String = "item-equipment",
        runId: String = "run-1",
        chunkId: String = "chunk-1",
    ) = SnapshotChunkReadyEvent(
        eventId = "evt-1",
        runId = runId,
        endpoint = endpoint,
        chunkId = chunkId,
        objectKey = "data/snapshots/$runId/$endpoint/chunks/$chunkId.jsonl.gz",
        recordCount = 100,
        uncompressedBytes = 5000,
        compressedBytes = 1000,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun chunkResult() = ChunkResult(
        recordCount = 100,
        successCount = 95,
        totalItems = 500,
        calculatedCount = 480,
        errorCount = 20,
        resultObjectKey = "calculator/runs/run-1/item-equipment/chunks/result-chunk-1.jsonl.gz",
        resultCount = 480,
        resultUncompressedBytes = 10000,
        resultCompressedBytes = 2000,
    )

    private suspend fun setupHappyPath(event: SnapshotChunkReadyEvent = testEvent()) {
        whenever(objectStorage.exists(event.objectKey)).thenReturn(true)
        whenever(objectStorage.exists(coordinator.resultObjectKeyFor(event))).thenReturn(false)
        whenever(chunkProcessor.process(any(), any())).thenReturn(chunkResult())
    }

    @Test
    fun `skips non-item-equipment endpoint without calling processor`() = runBlocking {
        val event = testEvent(endpoint = "character-basic")

        coordinator.handle(event)

        verify(chunkProcessor, never()).process(any(), any())
        val captor = argumentCaptor<ChunkProcessingEvent>()
        verify(metricsListener).onEvent(captor.capture())
        assertThat(captor.firstValue).isInstanceOf(ChunkProcessingEvent.Skipped::class.java)
        assertThat((captor.firstValue as ChunkProcessingEvent.Skipped).reason).isEqualTo("endpoint_mismatch")
    }

    @Test
    fun `skips when source artifact does not exist`() = runBlocking {
        val event = testEvent()
        whenever(objectStorage.exists(event.objectKey)).thenReturn(false)

        coordinator.handle(event)

        verify(chunkProcessor, never()).process(any(), any())
        val captor = argumentCaptor<ChunkProcessingEvent>()
        verify(metricsListener).onEvent(captor.capture())
        assertThat(captor.firstValue).isInstanceOf(ChunkProcessingEvent.Skipped::class.java)
        assertThat((captor.firstValue as ChunkProcessingEvent.Skipped).reason).isEqualTo("source_not_found")
    }

    @Test
    fun `republishes event when result already exists without calling processor`() = runBlocking {
        val event = testEvent()
        whenever(objectStorage.exists(event.objectKey)).thenReturn(true)
        whenever(objectStorage.exists(coordinator.resultObjectKeyFor(event))).thenReturn(true)

        coordinator.handle(event)

        verify(chunkProcessor, never()).process(any(), any())
        val captor = argumentCaptor<ChunkProcessingEvent>()
        verify(metricsListener).onEvent(captor.capture())
        assertThat(captor.firstValue).isInstanceOf(ChunkProcessingEvent.Skipped::class.java)
        assertThat((captor.firstValue as ChunkProcessingEvent.Skipped).reason).isEqualTo("result_exists")
        verify(resultEventPublisher).publishChunkReady(any())
    }

    @Test
    fun `processes chunk and publishes result on success`() = runBlocking {
        val event = testEvent()
        setupHappyPath(event)

        coordinator.handle(event)

        verify(chunkProcessor).process(any(), any())
        verify(resultEventPublisher).publishChunkReady(any())
        val captor = argumentCaptor<ChunkProcessingEvent>()
        verify(metricsListener).onEvent(captor.capture())
        assertThat(captor.firstValue).isInstanceOf(ChunkProcessingEvent.Completed::class.java)
    }

    @Test
    fun `completed event contains correct volume and processing data`() = runBlocking {
        val event = testEvent()
        val result = chunkResult()
        setupHappyPath(event)

        coordinator.handle(event)

        val captor = argumentCaptor<ChunkProcessingEvent>()
        verify(metricsListener).onEvent(captor.capture())
        val completed = captor.firstValue as ChunkProcessingEvent.Completed
        assertThat(completed.recordCount).isEqualTo(100)
        assertThat(completed.totalItems).isEqualTo(500)
        assertThat(completed.resultCount).isEqualTo(480)
        assertThat(completed.errorCount).isEqualTo(20)
        assertThat(completed.inputCompressedBytes).isEqualTo(1000L)
        assertThat(completed.inputUncompressedBytes).isEqualTo(5000L)
        assertThat(completed.resultCompressedBytes).isEqualTo(2000L)
        assertThat(completed.resultUncompressedBytes).isEqualTo(10000L)
    }

    @Test
    fun `records failure event when processor throws`() = runBlocking {
        val event = testEvent()
        whenever(objectStorage.exists(event.objectKey)).thenReturn(true)
        whenever(objectStorage.exists(coordinator.resultObjectKeyFor(event))).thenReturn(false)
        whenever(chunkProcessor.process(any(), any())).thenThrow(RuntimeException("boom"))

        assertThatThrownBy {
            runBlocking { coordinator.handle(event) }
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessage("boom")

        val captor = argumentCaptor<ChunkProcessingEvent>()
        verify(metricsListener).onEvent(captor.capture())
        assertThat(captor.firstValue).isInstanceOf(ChunkProcessingEvent.Failed::class.java)
        verify(resultEventPublisher, never()).publishChunkReady(any())
    }

    @Test
    fun `generates correct resultObjectKey format`() {
        val event = testEvent(runId = "run-42", chunkId = "chunk-7")

        assertThat(coordinator.resultObjectKeyFor(event))
            .isEqualTo("calculator/runs/run-42/item-equipment/chunks/result-chunk-7.jsonl.gz")
    }

    @Test
    fun `handles sequential calls without deadlock`() = runBlocking {
        val event = testEvent()
        setupHappyPath(event)

        coordinator.handle(event)
        coordinator.handle(event)

        verify(chunkProcessor, times(2)).process(any(), any())
    }

    @Test
    fun `republished event has zero counts and correct key`() = runBlocking {
        val event = testEvent(runId = "run-abc", chunkId = "chunk-xyz")
        whenever(objectStorage.exists(event.objectKey)).thenReturn(true)
        whenever(objectStorage.exists(coordinator.resultObjectKeyFor(event))).thenReturn(true)

        coordinator.handle(event)

        verify(resultEventPublisher).publishChunkReady(
            check { published ->
                assertThat(published.sourceRunId).isEqualTo("run-abc")
                assertThat(published.sourceChunkId).isEqualTo("chunk-xyz")
                assertThat(published.objectKey)
                    .isEqualTo("calculator/runs/run-abc/item-equipment/chunks/result-chunk-xyz.jsonl.gz")
                assertThat(published.resultCount).isEqualTo(0)
                assertThat(published.errorCount).isEqualTo(0)
                assertThat(published.uncompressedBytes).isEqualTo(0)
                assertThat(published.compressedBytes).isEqualTo(0)
            },
        )
    }

    @Test
    fun `published event on success contains processing results`() = runBlocking {
        val event = testEvent()
        val result = chunkResult()
        setupHappyPath(event)

        coordinator.handle(event)

        verify(resultEventPublisher).publishChunkReady(
            check { published ->
                assertThat(published.sourceRunId).isEqualTo("run-1")
                assertThat(published.sourceEndpoint).isEqualTo("item-equipment")
                assertThat(published.sourceChunkId).isEqualTo("chunk-1")
                assertThat(published.objectKey).isEqualTo(result.resultObjectKey)
                assertThat(published.sourceRecordCount).isEqualTo(100)
                assertThat(published.resultCount).isEqualTo(480)
                assertThat(published.errorCount).isEqualTo(20)
                assertThat(published.uncompressedBytes).isEqualTo(10000)
                assertThat(published.compressedBytes).isEqualTo(2000)
            },
        )
    }
}
