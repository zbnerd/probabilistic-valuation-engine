package maple.synchronizer.metrics

import maple.synchronizer.consumer.ChunkLifecycleEvent
import org.springframework.stereotype.Component

@Component
class SynchronizerChunkMetricsListener(
    private val metrics: SynchronizerMetrics,
) {
    fun onEvent(event: ChunkLifecycleEvent) {
        when (event) {
            is ChunkLifecycleEvent.Accepted -> onAccepted(event)
            is ChunkLifecycleEvent.Succeeded -> onSucceeded(event)
            is ChunkLifecycleEvent.Failed -> onFailed(event)
            is ChunkLifecycleEvent.Finally -> onFinally(event)
        }
    }

    private fun onAccepted(event: ChunkLifecycleEvent.Accepted) {
        metrics.incrementReceived()
        metrics.incrementProcessing()
    }

    private fun onSucceeded(event: ChunkLifecycleEvent.Succeeded) {
        metrics.incrementProcessed()
        metrics.recordStatusTransition("SUCCESS")
        metrics.chunkTimer().record(java.time.Duration.ofNanos(event.durationNanos))
        metrics.recordChunkBytes(event.compressedBytes)
        metrics.recordPreUpsertVolume(event.compressedBytes, event.uncompressedBytes, event.resultCount)
    }

    private fun onFailed(event: ChunkLifecycleEvent.Failed) {
        metrics.incrementFailed()
        metrics.recordStatusTransition("FAILED")
    }

    private fun onFinally(event: ChunkLifecycleEvent.Finally) {
        metrics.decrementProcessing()
    }
}
