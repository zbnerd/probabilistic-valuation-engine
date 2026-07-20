package maple.externalapi.snapshot

import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import maple.expectation.util.CompressionUtils
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.pipeline.artifact.write.ArtifactReceipt
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
    fun publishChunkReady(
        stats: ChunkStats,
        receipt: ArtifactReceipt,
        runId: String,
        endpoint: String,
    ): CompletableFuture<Void> {
        val chunkId = receipt.key.value.substringAfterLast('/').removeSuffix(".jsonl.gz")
        val ratio = CompressionUtils.ratioString(receipt.uncompressedBytes, receipt.compressedBytes)
        volumeMetrics.recordChunk(receipt.compressedBytes, receipt.uncompressedBytes, stats.recordCount.toLong())
        // Endpoint factory passes lowercase ("ranking-overall"); Micrometer tags
        // emitted by recordNexonBodyReceived use ExternalApiEndpoint.name which is
        // uppercase ("RANKING_OVERALL"). Normalize once here so the two counters
        // share one tag value, which makes `users_completed_total / nexon_total_ms_total`
        // math work per endpoint.
        val metricEndpoint = endpoint.uppercase().replace('-', '_')
        volumeMetrics.recordUsersCompleted(metricEndpoint, stats.recordCount.toLong())
        log.info(
            "[snapshotVolume] runId={} chunkId={} compressedBytes={} uncompressedBytes={} jsonRows={} compressionRatio={}",
            runId,
            chunkId,
            receipt.compressedBytes,
            receipt.uncompressedBytes,
            stats.recordCount,
            ratio,
        )

        val event = SnapshotChunkReadyEvent(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            endpoint = endpoint,
            chunkId = chunkId,
            objectKey = receipt.key.value,
            recordCount = stats.recordCount,
            uncompressedBytes = receipt.uncompressedBytes,
            compressedBytes = receipt.compressedBytes,
            sha256 = null,
            createdAt = java.time.Instant.now(clock),
        )
        return eventPublisher.publishChunkReady(event)
    }

    /**
     * Build [SnapshotRunCompletedEvent] from a finalized manifest and dispatch.
     * Caller must have set `manifest.finishedAt` before invoking.
     */
    fun publishRunCompleted(manifest: SnapshotChunkManifest, endpoint: String): CompletableFuture<Void> {
        val event = SnapshotRunCompletedEvent(
            eventId = UUID.randomUUID().toString(),
            runId = manifest.runId,
            endpoint = endpoint,
            manifestPath = maple.pipeline.artifact.identity.SourceArtifactLayout.manifest(manifest.runId, endpoint).value,
            totalRecords = manifest.totalRecords,
            totalFailed = manifest.totalFailed,
            chunkCount = manifest.chunks.size,
            startedAt = manifest.startedAt,
            finishedAt = requireNotNull(manifest.finishedAt),
            createdAt = java.time.Instant.now(clock),
        )
        return eventPublisher.publishRunCompleted(event)
    }

    /**
     * Build [SnapshotRunFailedEvent] carrying the writer-thread error message and dispatch.
     */
    fun publishRunFailed(
        manifest: SnapshotChunkManifest,
        endpoint: String,
        errorMessage: String,
    ): CompletableFuture<Void> {
        val event = SnapshotRunFailedEvent(
            eventId = UUID.randomUUID().toString(),
            runId = manifest.runId,
            endpoint = endpoint,
            errorMessage = errorMessage,
            createdAt = java.time.Instant.now(clock),
        )
        return eventPublisher.publishRunFailed(event)
    }
}
