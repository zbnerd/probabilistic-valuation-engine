package maple.expectation.infrastructure.pgmq

import java.time.Duration

/**
 * Result of a PGMQ worker's `processAsync()` invocation.
 *
 * - [Ack] — message processed successfully; archive.
 * - [Nack] — processing failed; retry per `retryable` flag, optionally resetting visibility window.
 * - [DeadLetter] — message cannot be processed; send to DLQ.
 *
 * Sealed class enables exhaustive `when` expressions at call sites.
 */
sealed class ProcessOutcome {

    /** Message processed successfully. Archive from PGMQ. */
    data object Ack : ProcessOutcome()

    /**
     * Message processing failed. Retry per [retryable] flag.
     *
     * @param retryable if true, requeue the message for retry. If false, send to DLQ.
     * @param visibilityReset optional override for the PGMQ visibility window. If null,
     *                       the PGMQ client's default visibility is used.
     */
    data class Nack(
        val retryable: Boolean,
        val visibilityReset: Duration? = null,
    ) : ProcessOutcome()

    /**
     * Message cannot be processed (poison message, validation failure, etc.).
     * Send to DLQ for manual inspection.
     */
    data class DeadLetter(
        val reason: String,
    ) : ProcessOutcome()
}