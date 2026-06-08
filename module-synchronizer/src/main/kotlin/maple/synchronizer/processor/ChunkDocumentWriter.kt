package maple.synchronizer.processor

import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.preparer.PreppedDocument
import maple.synchronizer.ranking.EquipmentRankingRedisWriter
import maple.synchronizer.repository.EquipmentReadModelRepository
import org.springframework.stereotype.Component

@Component
class ChunkDocumentWriter(
    private val readModelRepository: EquipmentReadModelRepository,
    private val rankingWriter: EquipmentRankingRedisWriter,
    private val metrics: SynchronizerMetrics,
) {

    fun write(runId: String, chunkId: String, prepped: List<PreppedDocument>) {
        metrics.mainUpsertTimer().record(
            Runnable {
                readModelRepository.bulkUpsert(runId, chunkId, prepped)
            },
        )
        rankingWriter.update(prepped)
    }
}
