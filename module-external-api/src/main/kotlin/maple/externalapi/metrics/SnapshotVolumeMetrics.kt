package maple.externalapi.metrics

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class SnapshotVolumeMetrics(private val registry: MeterRegistry) {

    private val compressedBytesTotal = registry.counter("external_api_snapshot_compressed_bytes_total")
    private val uncompressedBytesTotal = registry.counter("external_api_snapshot_uncompressed_bytes_total")
    private val jsonRowsTotal = registry.counter("external_api_snapshot_json_rows_total")

    private val compressedBytesSummary = DistributionSummary.builder("external_api_snapshot_compressed_bytes")
        .description("Compressed bytes per snapshot chunk")
        .register(registry)

    private val uncompressedBytesSummary = DistributionSummary.builder("external_api_snapshot_uncompressed_bytes")
        .description("Uncompressed bytes per snapshot chunk")
        .register(registry)

    private val compressionRatioSummary = DistributionSummary.builder("external_api_snapshot_compression_ratio")
        .description("Compression ratio (uncompressed/compressed) per snapshot chunk")
        .register(registry)

    fun recordChunk(compressedBytes: Long, uncompressedBytes: Long, jsonRows: Long) {
        compressedBytesTotal.increment(compressedBytes.toDouble())
        uncompressedBytesTotal.increment(uncompressedBytes.toDouble())
        jsonRowsTotal.increment(jsonRows.toDouble())
        compressedBytesSummary.record(compressedBytes.toDouble())
        uncompressedBytesSummary.record(uncompressedBytes.toDouble())
        if (compressedBytes > 0) {
            compressionRatioSummary.record(uncompressedBytes.toDouble() / compressedBytes.toDouble())
        }
    }

    /**
     * Per-endpoint user counter — increments by [count] when a chunk has been
     * successfully converted and published (snapshotVolume log line is the
     * success boundary). Lets ops derive per-endpoint user throughput via
     * `irate(external_api_users_completed_total{endpoint=...}[5m])`.
     */
    fun recordUsersCompleted(endpoint: String, count: Long) {
        io.micrometer.core.instrument.Counter.builder("external_api_users_completed_total")
            .tag("endpoint", endpoint)
            .register(registry)
            .increment(count.toDouble())
    }
}
