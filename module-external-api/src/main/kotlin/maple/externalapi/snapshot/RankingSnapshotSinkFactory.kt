package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Builds [ChunkedSnapshotSink] instances for the ranking phase. Owns the
 * `ObjectMapper` reference so [maple.externalapi.scheduler.phase.RankingFetchPhase]
 * can stay free of direct Jackson imports.
 */
@Component
class RankingSnapshotSinkFactory(
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val volumeMetrics: SnapshotVolumeMetrics,
    @Qualifier("rankingSnapshotPublisher")
    private val rankingPublisher: SnapshotChunkEventPublisher,
) {
    fun create(runDir: Path, endpoint: String): ChunkedSnapshotSink {
        val endpointConfig = chunkingProperties.configFor(endpoint)
        return ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = endpoint,
            maxRecords = endpointConfig.maxRecords,
            maxUncompressedBytes = endpointConfig.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = SinkEventPublisher(rankingPublisher),
            volumeMetrics = volumeMetrics,
        )
    }
}
