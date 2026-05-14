package maple.synchronizer.processor

import io.micrometer.core.instrument.Timer
import maple.expectation.common.event.CalculatorResultChunkReadyEvent
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
    private val preparer: EquipmentDocumentPreparer,
    private val readModelRepository: EquipmentReadModelRepository,
    private val metrics: SynchronizerMetrics,
) : ChunkProcessor {

    private val documentBuilder = EquipmentDocumentBuilder()

    private val log = LoggerFactory.getLogger(DefaultChunkProcessor::class.java)

    override fun process(event: CalculatorResultChunkReadyEvent): ChunkProcessResult {
        val grouped = timed(metrics.fileReadTimer()) {
            resultFileReader.readAndGroupByCompositeKey(event.objectKey)
        }

        val documents = timed(metrics.documentBuildTimer()) {
            grouped.map { documentBuilder.build(event.sourceRunId, event.sourceChunkId, it) }
        }

        val itemsCount = grouped.sumOf { it.items.size.toLong() }

        log.info("[Synchronizer] grouped {} results into {} documents", event.resultCount, documents.size)

        metrics.incrementDocuments(documents.size)
        metrics.incrementItems(itemsCount)
        metrics.recordChunkSize(documents.size, itemsCount, event.compressedBytes)
        documents.forEach { metrics.recordDocumentEquipment(it.summary.equipmentCount) }

        metrics.recordPreUpsertVolume(event.compressedBytes, event.uncompressedBytes, event.resultCount.toLong())
        val ratio = if (event.compressedBytes > 0)
            "%.2f".format(event.uncompressedBytes.toDouble() / event.compressedBytes.toDouble())
        else "N/A"
        log.info(
            "[preUpsertVolume] runId={} chunkId={} compressedBytes={} uncompressedBytes={} jsonRows={} documents={} compressionRatio={}",
            event.sourceRunId, event.sourceChunkId, event.compressedBytes, event.uncompressedBytes,
            event.resultCount, documents.size, ratio,
        )

        val prepped = preparer.prepare(documents)

        metrics.mainUpsertTimer().record(Runnable {
            readModelRepository.bulkUpsert(event.sourceRunId, event.sourceChunkId, prepped)
        })

        return ChunkProcessResult(
            documentCount = documents.size,
            itemCount = itemsCount,
            compressedBytes = event.compressedBytes,
            uncompressedBytes = event.uncompressedBytes,
            jsonRowCount = event.resultCount.toLong(),
        )
    }

    private inline fun <T> timed(timer: Timer, block: () -> T): T {
        val sample = Timer.start()
        return block().also { sample.stop(timer) }
    }
}
