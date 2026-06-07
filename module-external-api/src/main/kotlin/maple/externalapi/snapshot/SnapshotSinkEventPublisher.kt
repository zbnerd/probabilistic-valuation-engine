package maple.externalapi.snapshot

import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.util.CompressionUtils
import maple.externalapi.metrics.SnapshotVolumeMetrics
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/**
 * Owns snapshot-sink event DTO construction, volume-metrics recording, and
 * the `snapshotVolume` log line. Delegates the actual send to [SinkEventPublisher].
 *
 * Plain class (not a Spring bean) — each phase/factory constructs one with its
 * own [SinkEventPublisher] so per-endpoint Kafka routing is preserved. Every
 * method takes the call-site context (runId, endpoint, manifest) and returns
 * nothing.
 */
class SnapshotSinkEventPublisher(
    private val eventPublisher: SinkEventPublisher,
    private val volumeMetrics: SnapshotVolumeMetrics,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(SnapshotSinkEventPublisher::class.java)

    /**
     * Build [SnapshotChunkReadyEvent] for a finished chunk, record its size in
     * [volumeMetrics], emit the `snapshotVolume` log line, and dispatch.
     */
    fun publishChunkReady(stats: ChunkStats, runId: String, endpoint: String) {
        val chunkId = String.format("part-%06d", stats.partIndex)
        val ratio = CompressionUtils.ratioString(stats.uncompressedBytes, stats.compressedBytes)
        volumeMetrics.recordChunk(stats.compressedBytes, stats.uncompressedBytes, stats.recordCount.toLong())
        log.info(
            "[snapshotVolume] runId={} chunkId={} compressedBytes={} uncompressedBytes={} jsonRows={} compressionRatio={}",
            runId, chunkId, stats.compressedBytes, stats.uncompressedBytes, stats.recordCount, ratio,
        )

        val event = SnapshotChunkReadyEvent(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            endpoint = endpoint,
            chunkId = chunkId,
            objectKey = "runs/$runId/$endpoint/${stats.path}",
            recordCount = stats.recordCount,
            uncompressedBytes = stats.uncompressedBytes,
            compressedBytes = stats.compressedBytes,
            createdAt = java.time.Instant.now(clock),
        )
        eventPublisher.publishChunkReady(event)
    }
}
