package maple.synchronizer.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.atomic.AtomicInteger
import maple.expectation.common.event.ChunkExecutionType
import maple.synchronizer.state.ChunkExecutionStatus
import org.springframework.stereotype.Component

@Component
class SynchronizerMeterRegistry(private val registry: MeterRegistry) {

    // Chunk counters
    val chunksReceived = registry.counter("synchronizer_chunks_received_total")
    val chunksProcessing = AtomicInteger(0)
    val chunksProcessed = registry.counter("synchronizer_chunks_processed_total")
    val chunksFailed = registry.counter("synchronizer_chunks_failed_total")

    init {
        registry.gauge("synchronizer_chunks_processing", chunksProcessing)
    }

    // Document / item counters
    val documentsProcessed = registry.counter("synchronizer_documents_processed_total")
    val itemsProcessed = registry.counter("synchronizer_items_processed_total")

    // Timers — 각 단계별 latency
    val chunkTimer = Timer.builder("synchronizer_chunk_duration_seconds")
        .description("Total time to process a single chunk end-to-end")
        .publishPercentileHistogram()
        .register(registry)

    val fileReadTimer = Timer.builder("synchronizer_file_read_duration_seconds")
        .description("Time to read and decompress gzip JSONL file")
        .publishPercentileHistogram()
        .register(registry)

    val documentBuildTimer = Timer.builder("synchronizer_document_build_duration_seconds")
        .description("Time to build read model documents from grouped results")
        .publishPercentileHistogram()
        .register(registry)

    val mainUpsertTimer = Timer.builder("synchronizer_main_upsert_duration_seconds")
        .description("Time to bulk upsert documents into main read model table")
        .publishPercentileHistogram()
        .register(registry)

    // Distribution summaries — chunk 크기/분포
    val chunkDocumentsSummary = DistributionSummary.builder("synchronizer_chunk_documents")
        .description("Number of documents per chunk")
        .register(registry)

    val chunkItemsSummary = DistributionSummary.builder("synchronizer_chunk_items")
        .description("Number of items per chunk")
        .register(registry)

    val chunkBytesSummary = DistributionSummary.builder("synchronizer_chunk_bytes")
        .description("Compressed document bytes per chunk")
        .register(registry)

    val documentEquipmentSummary = DistributionSummary.builder("synchronizer_document_equipment_count")
        .description("Equipment count per document")
        .register(registry)

    // Volume metrics — pre-upsert data volume
    val preUpsertCompressedBytesTotal = registry.counter("synchronizer_pre_upsert_compressed_bytes_total")
    val preUpsertUncompressedBytesTotal = registry.counter("synchronizer_pre_upsert_uncompressed_bytes_total")
    val preUpsertJsonRowsTotal = registry.counter("synchronizer_pre_upsert_json_rows_total")

    val preUpsertCompressedSummary = DistributionSummary.builder("synchronizer_pre_upsert_compressed_bytes")
        .description("Compressed artifact bytes per chunk before DB upsert")
        .register(registry)

    val preUpsertUncompressedSummary = DistributionSummary.builder("synchronizer_pre_upsert_uncompressed_bytes")
        .description("Uncompressed artifact bytes per chunk before DB upsert")
        .register(registry)

    val preUpsertCompressionRatio = DistributionSummary.builder("synchronizer_pre_upsert_compression_ratio")
        .description("Compression ratio (uncompressed/compressed) per chunk before DB upsert")
        .register(registry)

    // Status / execution factory methods — these create per-tag meters on demand
    fun statusCounter(status: String): Counter = registry.counter("synchronizer_chunk_status_transition_total", "status", status)

    fun chunkExecutionCounter(name: String, executionType: ChunkExecutionType): Counter = registry.counter(name, "execution_type", executionType.name)

    fun chunkExecutionSkippedCounter(
        executionType: ChunkExecutionType,
        status: ChunkExecutionStatus,
    ): Counter = registry.counter(
        "chunk_execution_skipped_total",
        "execution_type",
        executionType.name,
        "status",
        status.name,
    )

    fun chunkExecutionFailedCounter(
        executionType: ChunkExecutionType,
        status: ChunkExecutionStatus,
        reason: String,
    ): Counter = registry.counter(
        "chunk_execution_failed_total",
        "execution_type",
        executionType.name,
        "status",
        status.name,
        "reason",
        reason,
    )
}
