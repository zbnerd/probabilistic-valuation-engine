package maple.synchronizer.state

import java.time.Instant
import maple.expectation.error.exception.ArtifactNotFoundException
import maple.synchronizer.consumer.ChunkExecutionProperties
import maple.synchronizer.repository.ChunkExecutionClaim
import maple.synchronizer.repository.ChunkExecutionState
import org.springframework.stereotype.Component

/**
 * Owns state-machine decisions for chunk execution. The consumer template
 * delegates here for: claim evaluation, ack-vs-redelivery, lease-reclaim
 * detection, and failure classification (retryable vs terminal).
 *
 * Stateless — every method takes the current state + input and returns
 * a decision. Spring bean so it can be injected + mocked.
 */
@Component
class ChunkExecutionStateMachine(
    private val properties: ChunkExecutionProperties,
) {
    /**
     * Whether the Kafka message should be acknowledged for this chunk state.
     *
     * State transition policy:
     * - SUCCEEDED → ack. The work is complete and the chunk will not be reprocessed.
     * - FAILED_TERMINAL → ack. Retries exhausted or non-retryable error; nothing more to do here.
     * - FAILED_RETRYABLE with `nextRetryAt` in the future → do NOT ack. Another worker
     *   will pick the chunk up when the backoff expires; preserve the message for redelivery.
     * - FAILED_RETRYABLE with past or null `nextRetryAt` → ack. The retry window has
     *   elapsed and the row is stale.
     * - PROCESSING with an active lease (`leaseUntil` in the future) → ack. Another worker
     *   is still processing this chunk; let it finish.
     * - PROCESSING with expired or null lease → do NOT ack. The lease has timed out and
     *   the chunk is reclaimable; preserve the message so the reclaim path runs.
     * - PENDING → do NOT ack. The row was just inserted; a worker is about to claim it.
     */
    fun shouldAcknowledge(state: ChunkExecutionState, now: Instant = Instant.now()): Boolean = when (val s = state.status) {
        is ChunkExecutionStatus.Succeeded,
        is ChunkExecutionStatus.FailedTerminal,
        -> true
        is ChunkExecutionStatus.FailedRetryable -> s.nextRetryAt?.isAfter(now) != true
        ChunkExecutionStatus.Processing -> state.leaseUntil?.isAfter(now) == true
        ChunkExecutionStatus.Pending -> false
    }

    /** Whether the message should be left unacked for Kafka redelivery. */
    fun shouldPreserveKafkaRedelivery(state: ChunkExecutionState, now: Instant = Instant.now()): Boolean {
        val s = state.status as? ChunkExecutionStatus.FailedRetryable ?: return false
        return s.nextRetryAt?.isAfter(now) == true
    }

    /** True when the processing lease has expired or was never set — this chunk is reclaimable. */
    fun isReclaimedExpired(state: ChunkExecutionState, now: Instant): Boolean = (state.status as? ChunkExecutionStatus.Processing)?.isReclaimed(state.leaseUntil, now) == true

    /** Classify a failure as retryable or terminal. */
    fun classifyFailure(
        ex: Throwable,
        claim: ChunkExecutionClaim,
    ): FailureDecision {
        val artifactMissing = ex is ArtifactNotFoundException
        val maxAttempts = if (artifactMissing) {
            properties.retry.artifactMissingMaxAttempts
        } else {
            properties.retry.maxAttempts
        }
        return if (claim.attemptCount >= maxAttempts) {
            val terminalReason = if (artifactMissing) ARTIFACT_MISSING_MAX_ATTEMPTS else MAX_ATTEMPTS_EXCEEDED
            FailureDecision.Terminal(
                attemptCount = claim.attemptCount,
                terminalReason = terminalReason,
            )
        } else {
            FailureDecision.Retryable(
                attemptCount = claim.attemptCount,
                nextRetryAt = Instant.now().plus(
                    properties.retryBaseBackoff.multipliedBy(claim.attemptCount.toLong()),
                ),
            )
        }
    }

    private companion object {
        private const val ARTIFACT_MISSING_MAX_ATTEMPTS = "ARTIFACT_MISSING_MAX_ATTEMPTS"
        private const val MAX_ATTEMPTS_EXCEEDED = "MAX_ATTEMPTS_EXCEEDED"
    }
}
