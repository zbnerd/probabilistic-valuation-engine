package maple.externalapi.snapshot

import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import maple.externalapi.metrics.SnapshotVolumeMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture

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

        val stats = ChunkStats(
            path = "chunks/part-000001.jsonl.gz",
            partIndex = 1,
            recordCount = 42,
            uncompressedBytes = 1000L,
            compressedBytes = 250L,
            startedAt = Instant.parse("2026-06-07T09:50:00Z"),
            finishedAt = Instant.parse("2026-06-07T09:55:00Z"),
        )

        publisher.publishChunkReady(stats, runId = "run-1", endpoint = "result")

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
        assertThat(event.createdAt).isEqualTo(Instant.parse("2026-06-07T10:00:00Z"))
        assertThat(event.eventId).isNotBlank()
    }

    @Test
    fun `publishChunkReady records volume metrics with compressed uncompressed and count`() {
        whenever(sinkEventPublisher.publishChunkReady(any())).thenReturn(CompletableFuture.completedFuture(null))

        val stats = ChunkStats(
            path = "chunks/part-000007.jsonl.gz",
            partIndex = 7,
            recordCount = 99,
            uncompressedBytes = 4096L,
            compressedBytes = 1024L,
            startedAt = Instant.parse("2026-06-07T09:00:00Z"),
            finishedAt = Instant.parse("2026-06-07T09:10:00Z"),
        )

        publisher.publishChunkReady(stats, "run-2", "item")

        verify(volumeMetrics).recordChunk(1024L, 4096L, 99L)
    }
}
