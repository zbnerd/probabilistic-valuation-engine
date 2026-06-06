package maple.synchronizer.metrics

import io.micrometer.core.instrument.Timer
import maple.synchronizer.state.ChunkExecutionStatus
import maple.expectation.common.event.ChunkExecutionType
import org.springframework.stereotype.Component

@Component
class SynchronizerMetrics(private val meterRegistry: SynchronizerMeterRegistry) {

    fun incrementReceived() = meterRegistry.chunksReceived.increment()
    fun incrementProcessing() = meterRegistry.chunksProcessing.incrementAndGet()
    fun decrementProcessing() = meterRegistry.chunksProcessing.decrementAndGet()
    fun incrementProcessed() = meterRegistry.chunksProcessed.increment()
    fun incrementFailed() = meterRegistry.chunksFailed.increment()

    fun recordChunkExecutionInserted(executionType: ChunkExecutionType) =
        meterRegistry.chunkExecutionCounter("chunk_execution_inserted_total", executionType).increment()

    fun recordChunkExecutionClaimed(executionType: ChunkExecutionType) =
        meterRegistry.chunkExecutionCounter("chunk_execution_claimed_total", executionType).increment()

    fun recordChunkExecutionSkipped(executionType: ChunkExecutionType, status: ChunkExecutionStatus) =
        meterRegistry.chunkExecutionSkippedCounter(executionType, status).increment()

    fun recordChunkExecutionSucceeded(executionType: ChunkExecutionType) =
        meterRegistry.chunkExecutionCounter("chunk_execution_succeeded_total", executionType).increment()

    fun recordChunkExecutionFailed(
        executionType: ChunkExecutionType,
        status: ChunkExecutionStatus,
        reason: String,
    ) = meterRegistry.chunkExecutionFailedCounter(executionType, status, reason).increment()

    fun recordChunkExecutionReclaimedExpired(executionType: ChunkExecutionType) =
        meterRegistry.chunkExecutionCounter("chunk_execution_reclaimed_expired_total", executionType).increment()

    fun incrementDocuments(count: Int) = meterRegistry.documentsProcessed.increment(count.toDouble())
    fun incrementItems(count: Long) = meterRegistry.itemsProcessed.increment(count.toDouble())

    fun recordStatusTransition(status: String) = meterRegistry.statusCounter(status).increment()

    fun recordChunkSize(documents: Int, items: Long) {
        meterRegistry.chunkDocumentsSummary.record(documents.toDouble())
        meterRegistry.chunkItemsSummary.record(items.toDouble())
    }

    fun recordChunkBytes(bytes: Long) {
        meterRegistry.chunkBytesSummary.record(bytes.toDouble())
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

    fun chunkTimer(): Timer = meterRegistry.chunkTimer
    fun fileReadTimer(): Timer = meterRegistry.fileReadTimer
    fun documentBuildTimer(): Timer = meterRegistry.documentBuildTimer
    fun mainUpsertTimer(): Timer = meterRegistry.mainUpsertTimer
}
