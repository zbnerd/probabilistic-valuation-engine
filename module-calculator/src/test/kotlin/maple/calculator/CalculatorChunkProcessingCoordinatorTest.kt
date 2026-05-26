package maple.calculator

import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.runBlocking
import maple.expectation.common.event.CalculatorResultChunkReadyEvent
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.calculator.event.KafkaResultEventPublisher
import maple.calculator.metrics.CalculatorMetrics
import maple.calculator.metrics.CalculatorVolumeMetrics
import maple.calculator.model.ChunkResult
import maple.calculator.processor.SnapshotChunkProcessor
import maple.calculator.storage.ObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class CalculatorChunkProcessingCoordinatorTest {

    @Mock
    private lateinit var chunkProcessor: SnapshotChunkProcessor

    @Mock
    private lateinit var resultEventPublisher: KafkaResultEventPublisher

    @Mock
    private lateinit var objectStorage: ObjectStorage

    @Mock
    private lateinit var metrics: CalculatorMetrics

    @Mock
    private lateinit var volumeMetrics: CalculatorVolumeMetrics

    @Mock
    private lateinit var chunkTimer: Timer

    private lateinit var coordinator: CalculatorChunkProcessingCoordinator

    @BeforeEach
    fun setUp() {
        coordinator = CalculatorChunkProcessingCoordinator(
            chunkProcessor, resultEventPublisher, objectStorage, metrics, volumeMetrics,
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
        whenever(metrics.timer()).thenReturn(chunkTimer)
    }

    @Test
    fun `skips non-item-equipment endpoint without calling processor`() = runBlocking {
        val event = testEvent(endpoint = "character-basic")

        coordinator.handle(event)

        verify(chunkProcessor, never()).process(any(), any())
        verify(metrics).recordChunkSkippedEndpoint()
    }

    @Test
    fun `skips when source artifact does not exist`() = runBlocking {
        val event = testEvent()
        whenever(objectStorage.exists(event.objectKey)).thenReturn(false)

        coordinator.handle(event)

        verify(chunkProcessor, never()).process(any(), any())
        verify(metrics).recordChunkSkippedNotFound()
    }

    @Test
    fun `republishes event when result already exists without calling processor`() = runBlocking {
        val event = testEvent()
        whenever(objectStorage.exists(event.objectKey)).thenReturn(true)
        whenever(objectStorage.exists(coordinator.resultObjectKeyFor(event))).thenReturn(true)

        coordinator.handle(event)

        verify(chunkProcessor, never()).process(any(), any())
        verify(metrics).recordChunkSkippedIdempotent()
        verify(resultEventPublisher).publishChunkReady(any())
    }

    @Test
    fun `processes chunk and publishes result on success`() = runBlocking {
        val event = testEvent()
        setupHappyPath(event)

        coordinator.handle(event)

        verify(chunkProcessor).process(any(), any())
        verify(resultEventPublisher).publishChunkReady(any())
        verify(metrics).recordChunkProcessed()
        verify(chunkTimer).record(any<Duration>())
    }

    @Test
    fun `records volume metrics on success`() = runBlocking {
        val event = testEvent()
        val result = chunkResult()
        setupHappyPath(event)

        coordinator.handle(event)

        verify(volumeMetrics).recordInput(event.compressedBytes, event.uncompressedBytes)
        verify(volumeMetrics).recordResult(result.resultCompressedBytes, result.resultUncompressedBytes, result.resultCount.toLong())
    }

    @Test
    fun `records chunk-level metrics on success`() = runBlocking {
        val event = testEvent()
        setupHappyPath(event)

        coordinator.handle(event)

        verify(metrics).recordChunkProcessed()
        verify(metrics).recordUsers(100)
        verify(metrics).recordItems(500)
        verify(metrics).recordCalculated(480)
        verify(metrics).recordErrors(20)
        verify(metrics).recordChunkRates(any(), any(), any())
    }

    @Test
    fun `records failure metric when processor throws`() = runBlocking {
        val event = testEvent()
        whenever(objectStorage.exists(event.objectKey)).thenReturn(true)
        whenever(objectStorage.exists(coordinator.resultObjectKeyFor(event))).thenReturn(false)
        whenever(chunkProcessor.process(any(), any())).thenThrow(RuntimeException("boom"))

        assertThatThrownBy {
            runBlocking { coordinator.handle(event) }
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessage("boom")

        verify(metrics).recordChunkFailed()
        verify(resultEventPublisher, never()).publishChunkReady(any())
        verify(metrics, never()).recordChunkProcessed()
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

        verify(resultEventPublisher).publishChunkReady(check { published ->
            assertThat(published.sourceRunId).isEqualTo("run-abc")
            assertThat(published.sourceChunkId).isEqualTo("chunk-xyz")
            assertThat(published.objectKey)
                .isEqualTo("calculator/runs/run-abc/item-equipment/chunks/result-chunk-xyz.jsonl.gz")
            assertThat(published.resultCount).isEqualTo(0)
            assertThat(published.errorCount).isEqualTo(0)
            assertThat(published.uncompressedBytes).isEqualTo(0)
            assertThat(published.compressedBytes).isEqualTo(0)
        })
    }

    @Test
    fun `published event on success contains processing results`() = runBlocking {
        val event = testEvent()
        val result = chunkResult()
        setupHappyPath(event)

        coordinator.handle(event)

        verify(resultEventPublisher).publishChunkReady(check { published ->
            assertThat(published.sourceRunId).isEqualTo("run-1")
            assertThat(published.sourceEndpoint).isEqualTo("item-equipment")
            assertThat(published.sourceChunkId).isEqualTo("chunk-1")
            assertThat(published.objectKey).isEqualTo(result.resultObjectKey)
            assertThat(published.sourceRecordCount).isEqualTo(100)
            assertThat(published.resultCount).isEqualTo(480)
            assertThat(published.errorCount).isEqualTo(20)
            assertThat(published.uncompressedBytes).isEqualTo(10000)
            assertThat(published.compressedBytes).isEqualTo(2000)
        })
    }
}
