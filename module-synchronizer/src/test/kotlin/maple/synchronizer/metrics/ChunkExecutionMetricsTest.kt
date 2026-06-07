package maple.synchronizer.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.expectation.common.event.ChunkExecutionType
import maple.synchronizer.state.ChunkExecutionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChunkExecutionMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val meterRegistry = SynchronizerMeterRegistry(registry)
    private val metrics = ChunkExecutionMetrics(meterRegistry)

    @Test
    fun `recordChunkExecutionInserted increments chunk_execution_inserted_total`() {
        metrics.recordChunkExecutionInserted(ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK)

        val counter = registry.find("chunk_execution_inserted_total")
            .tag("execution_type", "SYNCHRONIZER_RESULT_CHUNK")
            .counter() ?: error("expected counter chunk_execution_inserted_total")
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordChunkExecutionClaimed increments chunk_execution_claimed_total`() {
        metrics.recordChunkExecutionClaimed(ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK)

        val counter = registry.find("chunk_execution_claimed_total")
            .tag("execution_type", "SYNCHRONIZER_RESULT_CHUNK")
            .counter() ?: error("expected counter chunk_execution_claimed_total")
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordChunkExecutionSkipped increments with status tag`() {
        metrics.recordChunkExecutionSkipped(
            ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK,
            ChunkExecutionStatus.Succeeded,
        )

        val counter = registry.find("chunk_execution_skipped_total")
            .tag("execution_type", "SYNCHRONIZER_RESULT_CHUNK")
            .tag("status", "SUCCEEDED")
            .counter() ?: error("expected counter chunk_execution_skipped_total")
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordChunkExecutionSucceeded increments chunk_execution_succeeded_total`() {
        metrics.recordChunkExecutionSucceeded(ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK)

        val counter = registry.find("chunk_execution_succeeded_total")
            .tag("execution_type", "SYNCHRONIZER_RESULT_CHUNK")
            .counter() ?: error("expected counter chunk_execution_succeeded_total")
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordChunkExecutionFailed increments with status and reason tags`() {
        metrics.recordChunkExecutionFailed(
            ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK,
            ChunkExecutionStatus.FailedRetryable(java.time.Instant.now()),
            "TIMEOUT",
        )

        val counter = registry.find("chunk_execution_failed_total")
            .tag("execution_type", "SYNCHRONIZER_RESULT_CHUNK")
            .tag("status", "FAILED_RETRYABLE")
            .tag("reason", "TIMEOUT")
            .counter() ?: error("expected counter chunk_execution_failed_total")
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordChunkExecutionReclaimedExpired increments chunk_execution_reclaimed_expired_total`() {
        metrics.recordChunkExecutionReclaimedExpired(ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK)

        val counter = registry.find("chunk_execution_reclaimed_expired_total")
            .tag("execution_type", "SYNCHRONIZER_RESULT_CHUNK")
            .counter() ?: error("expected counter chunk_execution_reclaimed_expired_total")
        assertThat(counter.count()).isEqualTo(1.0)
    }
}
