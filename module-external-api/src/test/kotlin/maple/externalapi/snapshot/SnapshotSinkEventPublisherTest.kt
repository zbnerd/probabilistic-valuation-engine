package maple.externalapi.snapshot

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.write.ArtifactReceipt
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class SnapshotSinkEventPublisherTest {

    private val sinkEventPublisher = mock<SinkEventPublisher>()
    private val volumeMetrics = mock<SnapshotVolumeMetrics>()
    private val fixedClock = Clock.fixed(Instant.parse("2026-06-07T10:00:00Z"), ZoneOffset.UTC)

    private val publisher = SnapshotSinkEventPublisher(
        eventPublisher = sinkEventPublisher,
        volumeMetrics = volumeMetrics,
        clock = fixedClock,
    )

    @Test
    fun `publishChunkReady builds event with chunkId objectKey and createdAt`() {
        whenever(sinkEventPublisher.publishChunkReady(any())).thenReturn(CompletableFuture.completedFuture(null))

        val receipt = receipt(
            key = "runs/run-1/result/chunks/part-000001.jsonl.gz",
            compressedBytes = 250L,
            uncompressedBytes = 1000L,
        )
        val stats = ChunkStats(
            partIndex = 1,
            recordCount = 42,
            uncompressedBytes = 999L,
            startedAt = Instant.parse("2026-06-07T09:50:00Z"),
            finishedAt = Instant.parse("2026-06-07T09:55:00Z"),
            uploadFuture = CompletableFuture.completedFuture(receipt),
        )

        publisher.publishChunkReady(stats, receipt, runId = "run-1", endpoint = "result")

        val captor = argumentCaptor<SnapshotChunkReadyEvent>()
        verify(sinkEventPublisher).publishChunkReady(captor.capture())
        val event = captor.firstValue
        assertThat(event.runId).isEqualTo("run-1")
        assertThat(event.endpoint).isEqualTo("result")
        assertThat(event.chunkId).isEqualTo("part-000001")
        assertThat(event.objectKey).isEqualTo("runs/run-1/result/chunks/part-000001.jsonl.gz")
        assertThat(event.recordCount).isEqualTo(42)
        assertThat(event.uncompressedBytes).isEqualTo(1000L)
        assertThat(event.compressedBytes).isEqualTo(250L)
        assertThat(event.sha256).isNull()
        assertThat(event.createdAt).isEqualTo(Instant.parse("2026-06-07T10:00:00Z"))
        assertThat(event.eventId).isNotBlank()
    }

    @Test
    fun `publishChunkReady records volume metrics with compressed uncompressed and count`() {
        whenever(sinkEventPublisher.publishChunkReady(any())).thenReturn(CompletableFuture.completedFuture(null))

        val receipt = receipt(
            key = "runs/run-2/item/chunks/part-000007.jsonl.gz",
            compressedBytes = 1024L,
            uncompressedBytes = 4096L,
        )
        val stats = ChunkStats(
            partIndex = 7,
            recordCount = 99,
            uncompressedBytes = 4000L,
            startedAt = Instant.parse("2026-06-07T09:00:00Z"),
            finishedAt = Instant.parse("2026-06-07T09:10:00Z"),
            uploadFuture = CompletableFuture.completedFuture(receipt),
        )

        publisher.publishChunkReady(stats, receipt, "run-2", "item")

        verify(volumeMetrics).recordChunk(1024L, 4096L, 99L)
    }

    @Test
    fun `publishRunCompleted builds event from manifest with manifestPath and counts`() {
        whenever(sinkEventPublisher.publishRunCompleted(any())).thenReturn(CompletableFuture.completedFuture(null))

        val manifest = SnapshotChunkManifest(
            runId = "run-3",
            endpoint = "result",
            startedAt = Instant.parse("2026-06-07T08:00:00Z"),
        ).apply {
            totalRecords = 123
            totalFailed = 4
            finishedAt = Instant.parse("2026-06-07T09:00:00Z")
            chunks.add(
                ChunkEntry(
                    path = "chunks/part-000001.jsonl.gz",
                    recordCount = 123,
                    uncompressedBytes = 4096L,
                    compressedBytes = 1024L,
                    startedAt = Instant.parse("2026-06-07T08:30:00Z"),
                    finishedAt = Instant.parse("2026-06-07T08:35:00Z"),
                ),
            )
        }

        publisher.publishRunCompleted(manifest, endpoint = "result")

        val captor = argumentCaptor<SnapshotRunCompletedEvent>()
        verify(sinkEventPublisher).publishRunCompleted(captor.capture())
        val event = captor.firstValue
        assertThat(event.runId).isEqualTo("run-3")
        assertThat(event.endpoint).isEqualTo("result")
        assertThat(event.manifestPath).isEqualTo("runs/run-3/result/manifest.json")
        assertThat(event.totalRecords).isEqualTo(123)
        assertThat(event.totalFailed).isEqualTo(4)
        assertThat(event.chunkCount).isEqualTo(1)
        assertThat(event.startedAt).isEqualTo(Instant.parse("2026-06-07T08:00:00Z"))
        assertThat(event.finishedAt).isEqualTo(Instant.parse("2026-06-07T09:00:00Z"))
        assertThat(event.createdAt).isEqualTo(Instant.parse("2026-06-07T10:00:00Z"))
        assertThat(event.eventId).isNotBlank()
        verifyNoInteractions(volumeMetrics)
    }

    @Test
    fun `publishRunFailed builds event with error message and dispatches`() {
        whenever(sinkEventPublisher.publishRunFailed(any())).thenReturn(CompletableFuture.completedFuture(null))

        val manifest = SnapshotChunkManifest(
            runId = "run-4",
            endpoint = "item",
            startedAt = Instant.parse("2026-06-07T07:00:00Z"),
        )

        publisher.publishRunFailed(manifest, endpoint = "item", errorMessage = "writer thread died")

        val captor = argumentCaptor<SnapshotRunFailedEvent>()
        verify(sinkEventPublisher).publishRunFailed(captor.capture())
        val event = captor.firstValue
        assertThat(event.runId).isEqualTo("run-4")
        assertThat(event.endpoint).isEqualTo("item")
        assertThat(event.errorMessage).isEqualTo("writer thread died")
        assertThat(event.createdAt).isEqualTo(Instant.parse("2026-06-07T10:00:00Z"))
        assertThat(event.eventId).isNotBlank()
        verifyNoInteractions(volumeMetrics)
    }

    @Test
    fun `sink publisher preserves synchronous send failure`() {
        val delegate = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>()
        whenever(delegate.publishRunCompleted(any())).thenThrow(IllegalStateException("sync broker failure"))
        val safePublisher = SinkEventPublisher(delegate)

        val failure = awaitFailure(
            safePublisher.publishRunCompleted(
                SnapshotRunCompletedEvent(
                    eventId = "event-1",
                    runId = "run-1",
                    endpoint = "item-equipment",
                    manifestPath = "runs/run-1/item-equipment/manifest.json",
                    totalRecords = 0,
                    totalFailed = 0,
                    chunkCount = 0,
                    startedAt = Instant.parse("2026-06-07T09:00:00Z"),
                    finishedAt = Instant.parse("2026-06-07T10:00:00Z"),
                    createdAt = Instant.parse("2026-06-07T10:00:00Z"),
                ),
            ),
        )

        assertThat(failure).hasMessage("sync broker failure")
    }

    @Test
    fun `sink publisher preserves asynchronous send failure`() {
        val delegate = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>()
        whenever(delegate.publishRunFailed(any()))
            .thenReturn(CompletableFuture.failedFuture(IllegalStateException("async broker failure")))
        val safePublisher = SinkEventPublisher(delegate)

        val failure = awaitFailure(
            safePublisher.publishRunFailed(
                SnapshotRunFailedEvent(
                    eventId = "event-2",
                    runId = "run-1",
                    endpoint = "item-equipment",
                    errorMessage = "source failure",
                    createdAt = Instant.parse("2026-06-07T10:00:00Z"),
                ),
            ),
        )

        assertThat(failure).hasRootCauseMessage("async broker failure")
    }

    @Test
    fun `snapshot publisher returns required publication future`() {
        val requiredSend = CompletableFuture<Void>()
        whenever(sinkEventPublisher.publishRunCompleted(any())).thenReturn(requiredSend)
        val manifest = SnapshotChunkManifest(
            runId = "run-required",
            endpoint = "item-equipment",
            startedAt = Instant.parse("2026-06-07T08:00:00Z"),
            finishedAt = Instant.parse("2026-06-07T09:00:00Z"),
        )

        val returned = publisher.publishRunCompleted(manifest, "item-equipment")

        assertThat(returned).isSameAs(requiredSend)
    }

    private fun awaitFailure(future: CompletableFuture<Void>): Throwable {
        val captured = AtomicReference<Throwable>()
        future.whenComplete { _, failure -> captured.set(failure) }
        await().atMost(Duration.ofSeconds(5)).until { captured.get() != null }
        return requireNotNull(captured.get())
    }

    private fun receipt(
        key: String,
        compressedBytes: Long,
        uncompressedBytes: Long = 0L,
    ): ArtifactReceipt = ArtifactReceipt(
        key = ArtifactKey.require(key),
        compressedBytes = compressedBytes,
        uncompressedBytes = uncompressedBytes,
        contentSha256 = "fixture-sha256",
        backendTag = null,
    )
}
