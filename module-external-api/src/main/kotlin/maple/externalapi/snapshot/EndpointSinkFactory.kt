package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock

/**
 * Single factory for [ChunkedSnapshotSink] across all endpoint phases.
 * Owns [ObjectMapper], [SnapshotChunkingProperties], [SnapshotVolumeMetrics], and [Clock]
 * so callers do not have to thread them through their constructors.
 *
 * Replaces [RankingSnapshotSinkFactory] (now removed). Each endpoint has its own
 * publisher qualifier wired by Spring.
 */
@Component
class EndpointSinkFactory(
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val volumeMetrics: SnapshotVolumeMetrics,
    @Qualifier("characterBasicSnapshotPublisher")
    private val characterBasicPublisher: SnapshotChunkEventPublisher,
    @Qualifier("rankingSnapshotPublisher")
    private val rankingPublisher: SnapshotChunkEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun createForCharacterBasic(runDir: Path): ChunkedSnapshotSink =
        build(runDir, "character-basic", characterBasicPublisher)

    fun createForItemEquipment(runDir: Path): ChunkedSnapshotSink =
        build(runDir, "item-equipment", characterBasicPublisher)

    fun createForRanking(runDir: Path): ChunkedSnapshotSink =
        build(runDir, "ranking-overall", rankingPublisher)

    private fun build(
        runDir: Path,
        endpoint: String,
        publisher: SnapshotChunkEventPublisher,
    ): ChunkedSnapshotSink {
        val endpointConfig = chunkingProperties.configFor(endpoint)
        return ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = endpoint,
            maxRecords = endpointConfig.maxRecords,
            maxUncompressedBytes = endpointConfig.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = SnapshotSinkEventPublisher(
                eventPublisher = SinkEventPublisher(publisher),
                volumeMetrics = volumeMetrics,
                clock = clock,
            ),
            clock = clock,
        )
    }
}
