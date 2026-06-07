package maple.synchronizer.adapter.chunk

import maple.core.domain.chunk.ChunkProcessInput
import maple.synchronizer.metrics.DocumentVolumeMetrics
import maple.synchronizer.processor.ChunkDataReader
import maple.synchronizer.processor.ChunkDocumentTransformer
import maple.synchronizer.processor.ChunkDocumentWriter
import maple.synchronizer.processor.ChunkProcessResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Pipeline orchestrator that runs the chunk stage chain in order:
 *   read (file + ocid resolve) → transform (build + prepare) → write (upsert + ranking).
 *
 * Each stage is a Spring `@Component` injected as a constructor dependency. Stage-specific
 * timers stay inside the stages; this orchestrator records only the aggregate metrics
 * (documents, items, chunk size, per-document equipment) via [DocumentVolumeMetrics].
 */
@Component
class ChunkPipelineOrchestrator(
    private val dataReader: ChunkDataReader,
    private val transformer: ChunkDocumentTransformer,
    private val writer: ChunkDocumentWriter,
    private val volumeMetrics: DocumentVolumeMetrics,
) {
    private val log = LoggerFactory.getLogger(ChunkPipelineOrchestrator::class.java)

    fun execute(input: ChunkProcessInput): ChunkProcessResult {
        val grouped = dataReader.read(input.objectKey)

        val transformResult = transformer.transform(input.sourceRunId, input.sourceChunkId, grouped)

        log.info(
            "[Synchronizer] grouped {} results into {} documents",
            input.resultCount,
            transformResult.documentCount,
        )

        volumeMetrics.incrementDocuments(transformResult.documentCount)
        volumeMetrics.incrementItems(transformResult.itemCount)
        volumeMetrics.recordChunkSize(transformResult.documentCount, transformResult.itemCount)
        transformResult.prepped.forEach { volumeMetrics.recordDocumentEquipment(it.equipmentCount) }

        writer.write(input.sourceRunId, input.sourceChunkId, transformResult.prepped)

        return ChunkProcessResult(
            documentCount = transformResult.documentCount,
            itemCount = transformResult.itemCount,
            jsonRowCount = input.resultCount.toLong(),
        )
    }
}
