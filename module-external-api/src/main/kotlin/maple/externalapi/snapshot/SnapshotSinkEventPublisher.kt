package maple.externalapi.snapshot

import java.time.Clock
import java.util.UUID
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import maple.expectation.util.CompressionUtils
import maple.externalapi.metrics.SnapshotVolumeMetrics
import org.slf4j.LoggerFactory

/**
 * Owns snapshot-sink event DTO construction, volume-metrics recording, and
 * the `snapshotVolume` log line. Delegates the actual send to [SinkEventPublisher].
 *
 * Plain class (not a Spring bean) — each phase/factory constructs one with its
 * own [SinkEventPublisher] so per-endpoint Kafka routing is preserved. Every
 * method takes the call-site context (runId, endpoint) and returns nothing.
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
        volumeMetrics.recordUsersCompleted(endpoint, stats.recordCount.toLong())
        log.info(
            "[snapshotVolume] runId={} chunkId={} compressedBytes={} uncompressedBytes={} jsonRows={} compressionRatio={}",
            runId,
            chunkId,
            stats.compressedBytes,
            stats.uncompressedBytes,
            stats.recordCount,
            ratio,
        )

        val event = SnapshotChunkReadyEvent(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            endpoint = endpoint,
            chunkId = chunkId,
            // Writer (ChunkFileManager.newChunkWriter) puts chunks under
            // `$runKey/chunks/...`. event.objectKey must match that path so
            // the calculator/sync consumers can resolve the chunk via
            // objectStorage.exists(event.objectKey). Without the /chunks/
            // segment the consumers see "source chunk not found" even
            // though the object exists.
            objectKey = "runs/$runId/$endpoint/chunks/${stats.path}",
            recordCount = stats.recordCount,
            uncompressedBytes = stats.uncompressedBytes,
            compressedBytes = stats.compressedBytes,
            createdAt = java.time.Instant.now(clock),
        )
        eventPublisher.publishChunkReady(event)
    }

    /**
     * Build [SnapshotRunCompletedEvent] from a finalized manifest and dispatch.
     * Caller must have set `manifest.finishedAt` before invoking.
     */
    fun publishRunCompleted(manifest: SnapshotChunkManifest, endpoint: String) {
        val event = SnapshotRunCompletedEvent(
            eventId = UUID.randomUUID().toString(),
            runId = manifest.runId,
            endpoint = endpoint,
            manifestPath = "runs/${manifest.runId}/$endpoint/manifest.json",
            totalRecords = manifest.totalRecords,
            totalFailed = manifest.totalFailed,
            chunkCount = manifest.chunks.size,
            startedAt = manifest.startedAt,
            finishedAt = requireNotNull(manifest.finishedAt),
            createdAt = java.time.Instant.now(clock),
        )
        eventPublisher.publishRunCompleted(event)
    }

    /**
     * Build [SnapshotRunFailedEvent] carrying the writer-thread error message and dispatch.
     */
    fun publishRunFailed(manifest: SnapshotChunkManifest, endpoint: String, errorMessage: String) {
        val event = SnapshotRunFailedEvent(
            eventId = UUID.randomUUID().toString(),
            runId = manifest.runId,
            endpoint = endpoint,
            errorMessage = errorMessage,
            createdAt = java.time.Instant.now(clock),
        )
        eventPublisher.publishRunFailed(event)
    }
}
