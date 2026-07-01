package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import maple.expectation.common.storage.ObjectStorage
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
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
    private val clock: Clock = Clock.systemUTC(),
) {
    fun createForCharacterBasic(runKey: String): ChunkedSnapshotSink = build(runKey, "character-basic", characterBasicPublisher)

    fun createForItemEquipment(runKey: String): ChunkedSnapshotSink = build(runKey, "item-equipment", characterBasicPublisher)

    fun createForRanking(runKey: String): ChunkedSnapshotSink = build(runKey, "ranking-overall", rankingPublisher)

    private fun build(
        runKey: String,
        endpoint: String,
        publisher: SnapshotChunkEventPublisher,
    ): ChunkedSnapshotSink {
        val endpointConfig = chunkingProperties.configFor(endpoint)
        val fileManager = ChunkFileManager(
            runKey = runKey,
            endpoint = endpoint,
            maxRecords = endpointConfig.maxRecords,
            maxUncompressedBytes = endpointConfig.maxUncompressedBytes,
            objectMapper = objectMapper,
            clock = clock,
            objectStorage = objectStorage,
            maxChunkAgeMs = endpointConfig.maxChunkAgeMs,
        )
        return ChunkedSnapshotSink(
            endpoint = endpoint,
            queueCapacity = chunkingProperties.queueCapacity,
            fileManager = fileManager,
            eventPublisher = SnapshotSinkEventPublisher(
                eventPublisher = SinkEventPublisher(publisher),
                volumeMetrics = volumeMetrics,
                clock = clock,
            ),
        )
    }
}
