package maple.synchronizer.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocumentVolumeMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val meterRegistry = SynchronizerMeterRegistry(registry)
    private val metrics = DocumentVolumeMetrics(meterRegistry)

    @Test
    fun `incrementDocuments adds to documentsProcessed counter`() {
        metrics.incrementDocuments(7)
        metrics.incrementDocuments(3)

        val counter = registry.find("synchronizer_documents_processed_total").counter()
            ?: error("expected counter synchronizer_documents_processed_total")
        assertThat(counter.count()).isEqualTo(10.0)
    }

    @Test
    fun `incrementItems adds to itemsProcessed counter`() {
        metrics.incrementItems(100L)
        metrics.incrementItems(50L)

        val counter = registry.find("synchronizer_items_processed_total").counter()
            ?: error("expected counter synchronizer_items_processed_total")
        assertThat(counter.count()).isEqualTo(150.0)
    }

    @Test
    fun `recordChunkSize records both documents and items summaries`() {
        metrics.recordChunkSize(documents = 12, items = 345L)

        val docSummary = registry.find("synchronizer_chunk_documents").summary()
            ?: error("expected summary synchronizer_chunk_documents")
        val itemSummary = registry.find("synchronizer_chunk_items").summary()
            ?: error("expected summary synchronizer_chunk_items")
        assertThat(docSummary.count()).isEqualTo(1L)
        assertThat(docSummary.totalAmount()).isEqualTo(12.0)
        assertThat(itemSummary.count()).isEqualTo(1L)
        assertThat(itemSummary.totalAmount()).isEqualTo(345.0)
    }

    @Test
    fun `recordDocumentEquipment records per-document equipment count summary`() {
        metrics.recordDocumentEquipment(8)
        metrics.recordDocumentEquipment(2)

        val summary = registry.find("synchronizer_document_equipment_count").summary()
            ?: error("expected summary synchronizer_document_equipment_count")
        assertThat(summary.count()).isEqualTo(2L)
        assertThat(summary.totalAmount()).isEqualTo(10.0)
    }

    @Test
    fun `recordPreUpsertVolume increments totals records summaries and computes ratio when compressed non-zero`() {
        metrics.recordPreUpsertVolume(compressedBytes = 100L, uncompressedBytes = 400L, jsonRows = 12L)

        val compressedCounter = registry.find("synchronizer_pre_upsert_compressed_bytes_total").counter()
            ?: error("expected counter synchronizer_pre_upsert_compressed_bytes_total")
        val uncompressedCounter = registry.find("synchronizer_pre_upsert_uncompressed_bytes_total").counter()
            ?: error("expected counter synchronizer_pre_upsert_uncompressed_bytes_total")
        val rowsCounter = registry.find("synchronizer_pre_upsert_json_rows_total").counter()
            ?: error("expected counter synchronizer_pre_upsert_json_rows_total")
        assertThat(compressedCounter.count()).isEqualTo(100.0)
        assertThat(uncompressedCounter.count()).isEqualTo(400.0)
        assertThat(rowsCounter.count()).isEqualTo(12.0)

        val ratio = registry.find("synchronizer_pre_upsert_compression_ratio").summary()
            ?: error("expected summary synchronizer_pre_upsert_compression_ratio")
        assertThat(ratio.count()).isEqualTo(1L)
        assertThat(ratio.totalAmount()).isEqualTo(4.0) // 400/100
    }

    @Test
    fun `recordPreUpsertVolume skips ratio when compressedBytes is zero`() {
        metrics.recordPreUpsertVolume(compressedBytes = 0L, uncompressedBytes = 100L, jsonRows = 0L)

        val ratio = registry.find("synchronizer_pre_upsert_compression_ratio").summary()
            ?: error("expected summary synchronizer_pre_upsert_compression_ratio")
        assertThat(ratio.count()).isEqualTo(0L)
    }
}
