package maple.synchronizer.metrics

import maple.synchronizer.state.ChunkExecutionStatus
import maple.expectation.common.event.ChunkExecutionType
import org.springframework.stereotype.Component

/**
 * Owns chunk-execution state-machine counters (inserted / claimed / skipped /
 * succeeded / failed / reclaimed). Delegates actual meter creation to
 * [SynchronizerMeterRegistry] so the per-tag factory logic stays in one place.
 */
@Component
class ChunkExecutionMetrics(private val meterRegistry: SynchronizerMeterRegistry) {

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
}
