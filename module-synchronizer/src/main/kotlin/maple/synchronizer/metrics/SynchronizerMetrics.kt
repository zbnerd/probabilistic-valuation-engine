package maple.synchronizer.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class SynchronizerMetrics(private val registry: MeterRegistry) {

    // Chunk counters
    private val chunksReceived = registry.counter("synchronizer_chunks_received_total")
    private val chunksProcessing = AtomicInteger(0)
    private val chunksProcessed = registry.counter("synchronizer_chunks_processed_total")
    private val chunksFailed = registry.counter("synchronizer_chunks_failed_total")

    init {
        registry.gauge("synchronizer_chunks_processing", chunksProcessing)
    }

    // Document / item counters
    private val documentsProcessed = registry.counter("synchronizer_documents_processed_total")
    private val itemsProcessed = registry.counter("synchronizer_items_processed_total")

    // Timers — 각 단계별 latency
    private val chunkTimer = Timer.builder("synchronizer_chunk_duration_seconds")
        .description("Total time to process a single chunk end-to-end")
        .publishPercentileHistogram()
        .register(registry)

    private val fileReadTimer = Timer.builder("synchronizer_file_read_duration_seconds")
        .description("Time to read and decompress gzip JSONL file")
        .publishPercentileHistogram()
        .register(registry)

    private val documentBuildTimer = Timer.builder("synchronizer_document_build_duration_seconds")
        .description("Time to build read model documents from grouped results")
        .publishPercentileHistogram()
        .register(registry)

    private val mainUpsertTimer = Timer.builder("synchronizer_main_upsert_duration_seconds")
        .description("Time to bulk upsert documents into main read model table")
        .publishPercentileHistogram()
        .register(registry)

    // Distribution summaries — chunk 크기/분포
    private val chunkDocumentsSummary = DistributionSummary.builder("synchronizer_chunk_documents")
        .description("Number of documents per chunk")
        .register(registry)

    private val chunkItemsSummary = DistributionSummary.builder("synchronizer_chunk_items")
        .description("Number of items per chunk")
        .register(registry)

    private val chunkBytesSummary = DistributionSummary.builder("synchronizer_chunk_bytes")
        .description("Compressed document bytes per chunk")
        .register(registry)

    private val documentEquipmentSummary = DistributionSummary.builder("synchronizer_document_equipment_count")
        .description("Equipment count per document")
        .register(registry)

    // Volume metrics — pre-upsert data volume
    private val preUpsertCompressedBytesTotal = registry.counter("synchronizer_pre_upsert_compressed_bytes_total")
    private val preUpsertUncompressedBytesTotal = registry.counter("synchronizer_pre_upsert_uncompressed_bytes_total")
    private val preUpsertJsonRowsTotal = registry.counter("synchronizer_pre_upsert_json_rows_total")

    private val preUpsertCompressedSummary = DistributionSummary.builder("synchronizer_pre_upsert_compressed_bytes")
        .description("Compressed artifact bytes per chunk before DB upsert")
        .register(registry)

    private val preUpsertUncompressedSummary = DistributionSummary.builder("synchronizer_pre_upsert_uncompressed_bytes")
        .description("Uncompressed artifact bytes per chunk before DB upsert")
        .register(registry)

    private val preUpsertCompressionRatio = DistributionSummary.builder("synchronizer_pre_upsert_compression_ratio")
        .description("Compression ratio (uncompressed/compressed) per chunk before DB upsert")
        .register(registry)

    // Status transition counter
    private fun statusCounter(status: String) =
        registry.counter("synchronizer_chunk_status_transition_total", "status", status)

    fun incrementReceived() = chunksReceived.increment()
    fun incrementProcessing() = chunksProcessing.incrementAndGet()
    fun decrementProcessing() = chunksProcessing.decrementAndGet()
    fun incrementProcessed() = chunksProcessed.increment()
    fun incrementFailed() = chunksFailed.increment()

    fun incrementDocuments(count: Int) = documentsProcessed.increment(count.toDouble())
    fun incrementItems(count: Long) = itemsProcessed.increment(count.toDouble())

    fun recordStatusTransition(status: String) = statusCounter(status).increment()

    fun recordChunkSize(documents: Int, items: Long, bytes: Long) {
        chunkDocumentsSummary.record(documents.toDouble())
        chunkItemsSummary.record(items.toDouble())
        chunkBytesSummary.record(bytes.toDouble())
    }

    fun recordDocumentEquipment(count: Int) = documentEquipmentSummary.record(count.toDouble())

    fun recordPreUpsertVolume(compressedBytes: Long, uncompressedBytes: Long, jsonRows: Long) {
        preUpsertCompressedBytesTotal.increment(compressedBytes.toDouble())
        preUpsertUncompressedBytesTotal.increment(uncompressedBytes.toDouble())
        preUpsertJsonRowsTotal.increment(jsonRows.toDouble())
        preUpsertCompressedSummary.record(compressedBytes.toDouble())
        preUpsertUncompressedSummary.record(uncompressedBytes.toDouble())
        if (compressedBytes > 0) {
            preUpsertCompressionRatio.record(uncompressedBytes.toDouble() / compressedBytes.toDouble())
        }
    }

    fun chunkTimer(): Timer = chunkTimer
    fun fileReadTimer(): Timer = fileReadTimer
    fun documentBuildTimer(): Timer = documentBuildTimer
    fun mainUpsertTimer(): Timer = mainUpsertTimer
}
