package maple.synchronizer.processor

import io.micrometer.core.instrument.Timer
import maple.synchronizer.builder.EquipmentDocumentBuilder
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.preparer.EquipmentDocumentPreparer
import maple.synchronizer.repository.EquipmentReadModelRepository
import maple.synchronizer.storage.ResultFileReader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DefaultChunkProcessor(
    private val resultFileReader: ResultFileReader,
    private val readModelRepository: EquipmentReadModelRepository,
    private val metrics: SynchronizerMetrics,
    objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
) : ChunkProcessor {

    private val documentBuilder = EquipmentDocumentBuilder()
    private val preparer = EquipmentDocumentPreparer(objectMapper)

    private val log = LoggerFactory.getLogger(DefaultChunkProcessor::class.java)

    override fun process(input: ChunkProcessInput): ChunkProcessResult {
        val grouped = timed(metrics.fileReadTimer()) {
            resultFileReader.readAndGroupByCompositeKey(input.objectKey)
        }

        val documents = timed(metrics.documentBuildTimer()) {
            grouped.map { documentBuilder.build(input.sourceRunId, input.sourceChunkId, it) }
        }

        val itemsCount = grouped.sumOf { it.items.size.toLong() }

        log.info("[Synchronizer] grouped {} results into {} documents", input.resultCount, documents.size)

        metrics.incrementDocuments(documents.size)
        metrics.incrementItems(itemsCount)
        metrics.recordChunkSize(documents.size, itemsCount)
        documents.forEach { metrics.recordDocumentEquipment(it.summary.equipmentCount) }

        val prepped = preparer.prepare(documents)

        metrics.mainUpsertTimer().record(Runnable {
            readModelRepository.bulkUpsert(input.sourceRunId, input.sourceChunkId, prepped)
        })

        return ChunkProcessResult(
            documentCount = documents.size,
            itemCount = itemsCount,
            jsonRowCount = input.resultCount.toLong(),
        )
    }

    private inline fun <T> timed(timer: Timer, block: () -> T): T {
        val sample = Timer.start()
        return block().also { sample.stop(timer) }
    }
}
