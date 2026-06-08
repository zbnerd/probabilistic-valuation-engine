package maple.synchronizer.metrics

import org.springframework.stereotype.Component

/**
 * Owns document / item volume counters and pre-upsert data-volume metrics
 * (compressed / uncompressed bytes, JSON row count, compression ratio).
 * Delegates actual meter creation to [SynchronizerMeterRegistry].
 */
@Component
class DocumentVolumeMetrics(private val meterRegistry: SynchronizerMeterRegistry) {

    fun incrementDocuments(count: Int) = meterRegistry.documentsProcessed.increment(count.toDouble())

    fun incrementItems(count: Long) = meterRegistry.itemsProcessed.increment(count.toDouble())

    fun recordChunkSize(documents: Int, items: Long) {
        meterRegistry.chunkDocumentsSummary.record(documents.toDouble())
        meterRegistry.chunkItemsSummary.record(items.toDouble())
    }

    fun recordDocumentEquipment(count: Int) = meterRegistry.documentEquipmentSummary.record(count.toDouble())

    fun recordPreUpsertVolume(compressedBytes: Long, uncompressedBytes: Long, jsonRows: Long) {
        meterRegistry.preUpsertCompressedBytesTotal.increment(compressedBytes.toDouble())
        meterRegistry.preUpsertUncompressedBytesTotal.increment(uncompressedBytes.toDouble())
        meterRegistry.preUpsertJsonRowsTotal.increment(jsonRows.toDouble())
        meterRegistry.preUpsertCompressedSummary.record(compressedBytes.toDouble())
        meterRegistry.preUpsertUncompressedSummary.record(uncompressedBytes.toDouble())
        if (compressedBytes > 0) {
            meterRegistry.preUpsertCompressionRatio.record(uncompressedBytes.toDouble() / compressedBytes.toDouble())
        }
    }
}
