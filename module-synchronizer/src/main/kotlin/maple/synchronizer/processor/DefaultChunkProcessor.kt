package maple.synchronizer.processor

import maple.core.domain.chunk.ChunkProcessInput
import maple.synchronizer.metrics.DocumentVolumeMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DefaultChunkProcessor(
    private val dataReader: ChunkDataReader,
    private val transformer: ChunkDocumentTransformer,
    private val writer: ChunkDocumentWriter,
    private val volumeMetrics: DocumentVolumeMetrics,
) : ChunkProcessor {

    private val log = LoggerFactory.getLogger(DefaultChunkProcessor::class.java)

    override fun process(input: ChunkProcessInput): ChunkProcessResult {
        val grouped = dataReader.read(input.objectKey)

        val transformResult = transformer.transform(input.sourceRunId, input.sourceChunkId, grouped)

        log.info("[Synchronizer] grouped {} results into {} documents", input.resultCount, transformResult.documentCount)

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
