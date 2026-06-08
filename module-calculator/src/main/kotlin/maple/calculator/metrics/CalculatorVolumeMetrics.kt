package maple.calculator.metrics

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class CalculatorVolumeMetrics(registry: MeterRegistry) {

    // Input (snapshot artifact read)
    private val inputCompressedBytesTotal = registry.counter("calculator_input_compressed_bytes_total")
    private val inputUncompressedBytesTotal = registry.counter("calculator_input_uncompressed_bytes_total")

    private val inputCompressedSummary = DistributionSummary.builder("calculator_input_compressed_bytes")
        .description("Compressed input bytes per chunk")
        .register(registry)

    private val inputUncompressedSummary = DistributionSummary.builder("calculator_input_uncompressed_bytes")
        .description("Uncompressed input bytes per chunk")
        .register(registry)

    // Result (calculator output artifact)
    private val resultCompressedBytesTotal = registry.counter("calculator_result_compressed_bytes_total")
    private val resultUncompressedBytesTotal = registry.counter("calculator_result_uncompressed_bytes_total")
    private val resultJsonRowsTotal = registry.counter("calculator_result_json_rows_total")

    private val resultCompressedSummary = DistributionSummary.builder("calculator_result_compressed_bytes")
        .description("Compressed result bytes per chunk")
        .register(registry)

    private val resultUncompressedSummary = DistributionSummary.builder("calculator_result_uncompressed_bytes")
        .description("Uncompressed result bytes per chunk")
        .register(registry)

    private val resultCompressionRatio = DistributionSummary.builder("calculator_result_compression_ratio")
        .description("Compression ratio (uncompressed/compressed) per result chunk")
        .register(registry)

    fun recordInput(compressedBytes: Long, uncompressedBytes: Long) {
        inputCompressedBytesTotal.increment(compressedBytes.toDouble())
        inputUncompressedBytesTotal.increment(uncompressedBytes.toDouble())
        inputCompressedSummary.record(compressedBytes.toDouble())
        inputUncompressedSummary.record(uncompressedBytes.toDouble())
    }

    fun recordResult(compressedBytes: Long, uncompressedBytes: Long, jsonRows: Long) {
        resultCompressedBytesTotal.increment(compressedBytes.toDouble())
        resultUncompressedBytesTotal.increment(uncompressedBytes.toDouble())
        resultJsonRowsTotal.increment(jsonRows.toDouble())
        resultCompressedSummary.record(compressedBytes.toDouble())
        resultUncompressedSummary.record(uncompressedBytes.toDouble())
        if (compressedBytes > 0) {
            resultCompressionRatio.record(uncompressedBytes.toDouble() / compressedBytes.toDouble())
        }
    }
}
