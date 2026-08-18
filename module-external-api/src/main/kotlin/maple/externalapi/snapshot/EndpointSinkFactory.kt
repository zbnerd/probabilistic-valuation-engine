package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import maple.expectation.common.storage.ObjectStorage
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import maple.pipeline.artifact.lifecycle.RunLifecycle
import maple.pipeline.artifact.write.ArtifactWriter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Single factory for [ChunkedSnapshotSink] across all endpoint phases.
 * Owns [ObjectMapper], [SnapshotChunkingProperties], [SnapshotVolumeMetrics], [Clock],
 * and [ObjectStorage] so callers do not have to thread them through their constructors.
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
    private val objectStorage: ObjectStorage,
    private val artifactWriter: ArtifactWriter,
    private val runLifecycle: RunLifecycle,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun createForCharacterBasic(runId: String): ChunkedSnapshotSink = build(runId, "character-basic", characterBasicPublisher)

    fun createForItemEquipment(runId: String): ChunkedSnapshotSink = build(runId, "item-equipment", characterBasicPublisher)

    fun createForRanking(runId: String): ChunkedSnapshotSink = build(runId, "ranking-overall", rankingPublisher)

    private fun build(
        runId: String,
        endpoint: String,
        publisher: SnapshotChunkEventPublisher,
    ): ChunkedSnapshotSink {
        val endpointConfig = chunkingProperties.configFor(endpoint)
        val fileManager = ChunkFileManager(
            runId = runId,
            endpoint = endpoint,
            maxRecords = endpointConfig.maxRecords,
            maxUncompressedBytes = endpointConfig.maxUncompressedBytes,
            objectMapper = objectMapper,
            clock = clock,
            objectStorage = objectStorage,
            artifactWriter = artifactWriter,
        )
        return ChunkedSnapshotSink(
            endpoint = endpoint,
            queueCapacity = chunkingProperties.queueCapacity,
            fileManager = fileManager,
            runLifecycle = runLifecycle,
            eventPublisher = SnapshotSinkEventPublisher(
                eventPublisher = SinkEventPublisher(publisher),
                volumeMetrics = volumeMetrics,
                clock = clock,
            ),
        )
    }
}
