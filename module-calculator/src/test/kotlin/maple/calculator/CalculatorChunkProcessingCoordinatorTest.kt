package maple.calculator

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import maple.calculator.config.PipelineProperties
import maple.calculator.event.ChunkProcessingEvent
import maple.calculator.event.KafkaResultEventPublisher
import maple.calculator.metrics.CalculatorMetricsListener
import maple.calculator.model.ChunkResult
import maple.calculator.processor.SnapshotChunkProcessor
import maple.calculator.runstate.CalculatorCurrentRunIdHolder
import maple.expectation.common.storage.ObjectStorage
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

    @Mock
    private lateinit var currentRunIdHolder: CalculatorCurrentRunIdHolder

    // No delay → retry gives the chunk an immediate second/third/... attempt
    // before declaring source_not_found. This keeps the existing "happy path"
    // tests fast (no real time elapses) while exercising the retry probe.
    private val pipelineProperties: PipelineProperties = PipelineProperties(
        sourceChunkRetryDelaysMs = listOf(0L, 0L, 0L, 0L, 0L),
    )

    private lateinit var coordinator: CalculatorChunkProcessingCoordinator

    @BeforeEach
    fun setUp() {
        // Default: holder has no daily runId and no known cycles — first poll /
        // ext-api down. With currentRunIdOrNull() == null, the coordinator's
        // runId check at the top of handle() is short-circuited, so the
        // `isKnownRunId` stub isn't reached. Tests that need the runId
        // branches override these stubs explicitly.
        coordinator = CalculatorChunkProcessingCoordinator(
            chunkProcessor = chunkProcessor,
            resultEventPublisher = resultEventPublisher,
            objectStorage = objectStorage,
            metricsListener = metricsListener,
            currentRunIdHolder = currentRunIdHolder,
            pipelineProperties = pipelineProperties,
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

    // --- Regression: cycle runId emitted by ExternalApiScheduler.runItemEquipmentPhase ---
    //
    // ext-api generates a per-cycle runId for item-equipment chunks
    // (separate from the daily runId). The coordinator previously dropped
    // these as `stale_run` because the cycle runId didn't match the daily
    // runId polled from /api/internal/run-status. The fix: trust the source
    // chunk's existence as the sole ground truth. Any chunk in storage
    // is processed, regardless of runId. The result_exists check makes
    // this safe for events that arrive twice or after a replay.
    //
    // The previous design (lazy discovery via a double-exists pattern)
    // proved brittle: MinIO headObject can return NoSuchKeyException
    // transiently under concurrent load, and the discovery branch
    // sometimes raced the writer's PUT. The single-exists approach is
    // both simpler and correct.

    @Test
    fun `processes chunk from cycle runId without consulting currentRunIdHolder`() = runBlocking {
        val cycle = "20260615-153236-842732622"
        val event = testEvent(runId = cycle, chunkId = "part-000001")
        whenever(objectStorage.exists(event.objectKey)).thenReturn(true)
        whenever(objectStorage.exists(coordinator.resultObjectKeyFor(event))).thenReturn(false)
        whenever(chunkProcessor.process(any(), any())).thenReturn(chunkResult())

        coordinator.handle(event)

        // Holder is never consulted for runId gating; only the source-chunk
        // existence matters. A cycle runId different from the daily runId
        // is processed the same way as a matching one.
        verify(currentRunIdHolder, never()).currentRunIdOrNull()
        verify(currentRunIdHolder, never()).isKnownRunId(any())
        verify(currentRunIdHolder, never()).discoverCycleRunId(any())
        verify(chunkProcessor).process(any(), any())
        verify(resultEventPublisher).publishChunkReady(any())
        val captor = argumentCaptor<ChunkProcessingEvent>()
        verify(metricsListener).onEvent(captor.capture())
        assertThat(captor.firstValue).isInstanceOf(ChunkProcessingEvent.Completed::class.java)
    }

    @Test
    fun `missing chunk from any runId is reported as source_not_found`() = runBlocking {
        val stale = "20260615-130329-724573504" // prior failed run, chunks gone
        val event = testEvent(runId = stale, chunkId = "part-000001")
        whenever(objectStorage.exists(event.objectKey)).thenReturn(false)

        coordinator.handle(event)

        verify(chunkProcessor, never()).process(any(), any())
        verify(currentRunIdHolder, never()).currentRunIdOrNull()
        val captor = argumentCaptor<ChunkProcessingEvent>()
        verify(metricsListener).onEvent(captor.capture())
        assertThat(captor.firstValue).isInstanceOf(ChunkProcessingEvent.Skipped::class.java)
        assertThat((captor.firstValue as ChunkProcessingEvent.Skipped).reason).isEqualTo("source_not_found")
    }

    // --- Regression: MinIO headObject race ---
    //
    // MinIO `s3.headObject` can return NoSuchKeyException transiently for an
    // object that does exist on disk, when the producer's PUT is in flight.
    // Single-shot existence check observed ~10% miss rate; the previous
    // pipeline dropped 83/84 chunks as `source_not_found` even though the
    // chunks were already on disk. The fix: retry the existence probe
    // with backoff before declaring source_not_found. Default schedule
    // (`[0, 100, 300, 1000, 3000]` ms) recovers most race victims.
    //
    // The test uses a zero-delay schedule to keep the test fast; the
    // behavior under real delays is exercised in production.

    @Test
    fun `retries source chunk existence and processes on eventual visibility`() = runBlocking {
        val event = testEvent(chunkId = "part-race")
        // First two probes fail (simulating the race), the third succeeds.
        whenever(objectStorage.exists(event.objectKey))
            .thenReturn(false)   // attempt 1
            .thenReturn(false)   // attempt 2
            .thenReturn(true)    // attempt 3 — chunk became visible
        whenever(objectStorage.exists(coordinator.resultObjectKeyFor(event))).thenReturn(false)
        whenever(chunkProcessor.process(any(), any())).thenReturn(chunkResult())

        coordinator.handle(event)

        verify(objectStorage, times(3)).exists(event.objectKey)
        verify(chunkProcessor).process(any(), any())
        verify(resultEventPublisher).publishChunkReady(any())
        val captor = argumentCaptor<ChunkProcessingEvent>()
        verify(metricsListener, times(2)).onEvent(captor.capture())
        // No Skipped for source_not_found — last event is the Completed.
        assertThat(captor.allValues.last()).isInstanceOf(ChunkProcessingEvent.Completed::class.java)
    }

    @Test
    fun `gives up on source chunk after exhausting retry schedule`() = runBlocking {
        val event = testEvent(chunkId = "part-truly-missing")
        // All five probes fail. Coordinator should give up and report
        // source_not_found, not loop forever.
        whenever(objectStorage.exists(event.objectKey)).thenReturn(false)

        coordinator.handle(event)

        verify(objectStorage, times(5)).exists(event.objectKey)
        verify(chunkProcessor, never()).process(any(), any())
        val captor = argumentCaptor<ChunkProcessingEvent>()
        verify(metricsListener).onEvent(captor.capture())
        assertThat(captor.firstValue).isInstanceOf(ChunkProcessingEvent.Skipped::class.java)
        assertThat((captor.firstValue as ChunkProcessingEvent.Skipped).reason).isEqualTo("source_not_found")
    }
}
