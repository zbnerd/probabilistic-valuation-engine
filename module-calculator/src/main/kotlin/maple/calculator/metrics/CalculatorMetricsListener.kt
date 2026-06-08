package maple.calculator.metrics

import maple.calculator.event.ChunkProcessingEvent
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class CalculatorMetricsListener(
    private val metrics: CalculatorMetrics,
    private val volumeMetrics: CalculatorVolumeMetrics,
) {
    fun onEvent(event: ChunkProcessingEvent) {
        when (event) {
            is ChunkProcessingEvent.Skipped -> onSkipped(event)
            is ChunkProcessingEvent.Failed -> onFailed(event)
            is ChunkProcessingEvent.Completed -> onCompleted(event)
        }
    }

    private fun onSkipped(event: ChunkProcessingEvent.Skipped) {
        when (event.reason) {
            "endpoint_mismatch" -> metrics.recordChunkSkippedEndpoint()
            "source_not_found" -> metrics.recordChunkSkippedNotFound()
            "result_exists" -> metrics.recordChunkSkippedIdempotent()
        }
    }

    private fun onFailed(event: ChunkProcessingEvent.Failed) {
        metrics.recordChunkFailed()
    }

    private fun onCompleted(event: ChunkProcessingEvent.Completed) {
        metrics.timer().record(Duration.ofNanos(event.durationNanos))
        metrics.recordChunkProcessed()
        metrics.recordUsers(event.recordCount)
        metrics.recordItems(event.totalItems)
        metrics.recordCalculated(event.resultCount)
        metrics.recordErrors(event.errorCount)
        val durationSec = event.durationNanos / 1_000_000_000.0
        metrics.recordChunkRates(event.recordCount, event.totalItems, durationSec)
        volumeMetrics.recordInput(event.inputCompressedBytes, event.inputUncompressedBytes)
        volumeMetrics.recordResult(event.resultCompressedBytes, event.resultUncompressedBytes, event.resultCount.toLong())
    }
}
