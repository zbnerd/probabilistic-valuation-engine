package maple.synchronizer.processor

import maple.core.domain.chunk.ChunkProcessInput
import maple.synchronizer.metrics.SynchronizerMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DefaultChunkProcessor(
    private val dataReader: ChunkDataReader,
    private val transformer: ChunkDocumentTransformer,
    private val writer: ChunkDocumentWriter,
    private val metrics: SynchronizerMetrics,
) : ChunkProcessor {

    private val log = LoggerFactory.getLogger(DefaultChunkProcessor::class.java)

    override fun process(input: ChunkProcessInput): ChunkProcessResult {
        val grouped = dataReader.read(input.objectKey)

        val transformResult = transformer.transform(input.sourceRunId, input.sourceChunkId, grouped)

        log.info("[Synchronizer] grouped {} results into {} documents", input.resultCount, transformResult.documentCount)

        metrics.incrementDocuments(transformResult.documentCount)
        metrics.incrementItems(transformResult.itemCount)
        metrics.recordChunkSize(transformResult.documentCount, transformResult.itemCount)
        transformResult.prepped.forEach { metrics.recordDocumentEquipment(it.equipmentCount) }

        writer.write(input.sourceRunId, input.sourceChunkId, transformResult.prepped)

        return ChunkProcessResult(
            documentCount = transformResult.documentCount,
            itemCount = transformResult.itemCount,
            jsonRowCount = input.resultCount.toLong(),
        )
    }
}
