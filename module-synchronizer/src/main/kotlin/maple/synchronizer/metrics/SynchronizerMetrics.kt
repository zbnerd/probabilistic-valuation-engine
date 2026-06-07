package maple.synchronizer.metrics

import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

/**
 * Owns chunk-lifecycle counters (received / processing / processed / failed),
 * the status-transition counter, the per-chunk compressed-bytes summary,
 * and timer accessors for the synchronizer pipeline stages.
 *
 * Domain-specific metric surfaces (chunk-execution state machine, document /
 * item volume, pre-upsert volume) live in [ChunkExecutionMetrics] and
 * [DocumentVolumeMetrics].
 */
@Component
class SynchronizerMetrics(private val meterRegistry: SynchronizerMeterRegistry) {

    fun incrementReceived() = meterRegistry.chunksReceived.increment()

    fun incrementProcessing() = meterRegistry.chunksProcessing.incrementAndGet()

    fun decrementProcessing() = meterRegistry.chunksProcessing.decrementAndGet()

    fun incrementProcessed() = meterRegistry.chunksProcessed.increment()

    fun incrementFailed() = meterRegistry.chunksFailed.increment()

    fun recordStatusTransition(status: String) = meterRegistry.statusCounter(status).increment()

    fun recordChunkBytes(bytes: Long) {
        meterRegistry.chunkBytesSummary.record(bytes.toDouble())
    }

    fun chunkTimer(): Timer = meterRegistry.chunkTimer

    fun fileReadTimer(): Timer = meterRegistry.fileReadTimer

    fun documentBuildTimer(): Timer = meterRegistry.documentBuildTimer

    fun mainUpsertTimer(): Timer = meterRegistry.mainUpsertTimer
}
