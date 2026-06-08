package maple.synchronizer.state

import java.time.Instant

sealed class ChunkExecutionStatus(val name: String) {
    abstract fun isTerminal(): Boolean

    /**
     * Whether the Kafka consumer should acknowledge the message (true) or leave it
     * unacked so Kafka redelivers it later (false).
     *
     * The contract: `true` = "this chunk is finished from our perspective, ack and move on".
     * `false` = "another worker is processing this, or Kafka should retry it later — preserve
     * the message for redelivery".
     *
     * Per-subtype policy is documented on each override.
     */
    abstract fun shouldAcknowledge(now: Instant): Boolean
    abstract fun shouldPreserveKafkaRedelivery(now: Instant): Boolean
    fun isTerminalSkip(): Boolean = this is Succeeded || this is FailedTerminal

    companion object {
        const val PENDING_NAME: String = "PENDING"
        const val PROCESSING_NAME: String = "PROCESSING"
        const val SUCCEEDED_NAME: String = "SUCCEEDED"
        const val FAILED_RETRYABLE_NAME: String = "FAILED_RETRYABLE"
        const val FAILED_TERMINAL_NAME: String = "FAILED_TERMINAL"

        fun fromName(s: String): ChunkExecutionStatus = when (s) {
            PENDING_NAME -> Pending
            PROCESSING_NAME -> Processing
            SUCCEEDED_NAME -> Succeeded
            FAILED_RETRYABLE_NAME -> FailedRetryable(null)
            FAILED_TERMINAL_NAME -> FailedTerminal(null)
            else -> throw IllegalArgumentException("Unknown ChunkExecutionStatus name: $s")
        }
    }

    /** Singleton — use `is Pending` checks, not `===`. */
    object Pending : ChunkExecutionStatus(PENDING_NAME) {
        override fun isTerminal(): Boolean = false

        // Acknowledge when state is PENDING? No — PENDING means the row was just inserted and
        // a worker is about to claim it. Leave unacked so the in-flight worker proceeds.
        override fun shouldAcknowledge(now: Instant): Boolean = false
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = false
    }

    /** Singleton — use `is Processing` checks, not `===`. */
    object Processing : ChunkExecutionStatus(PROCESSING_NAME) {
        override fun isTerminal(): Boolean = false

        // Acknowledge while PROCESSING? No — a worker holds the lease and is still running.
        // Leaving unacked lets Kafka redeliver if the worker dies; `leaseUntil` reclaim logic
        // handles the timeout case.
        override fun shouldAcknowledge(now: Instant): Boolean = false
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = false

        /** True when the lease has expired or was never set — this chunk is reclaimable. */
        fun isReclaimed(leaseUntil: Instant?, now: Instant): Boolean = leaseUntil?.isAfter(now) != true
    }

    /** Singleton — use `is Succeeded` checks, not `===`. */
    object Succeeded : ChunkExecutionStatus(SUCCEEDED_NAME) {
        override fun isTerminal(): Boolean = true

        // SUCCEEDED: work is done, the chunk will not be reprocessed. Acknowledge.
        override fun shouldAcknowledge(now: Instant): Boolean = true
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = false
    }

    data class FailedRetryable(val nextRetryAt: Instant?) : ChunkExecutionStatus(FAILED_RETRYABLE_NAME) {
        override fun isTerminal(): Boolean = false

        // FAILED_RETRYABLE with a future retry: another worker will pick it up — leave unacked
        // so Kafka redelivers when the backoff expires.
        // FAILED_RETRYABLE with past or null retry: the retry window has passed and the row
        // is stale; no worker will pick it up. Acknowledge to drain the queue.
        override fun shouldAcknowledge(now: Instant): Boolean = nextRetryAt?.isAfter(now) != true
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = nextRetryAt?.isAfter(now) == true
    }

    data class FailedTerminal(val reason: String?) : ChunkExecutionStatus(FAILED_TERMINAL_NAME) {
        override fun isTerminal(): Boolean = true

        // FAILED_TERMINAL: chunk exhausted retries or hit a non-retryable error. Work is
        // permanently done from this consumer's perspective. Acknowledge.
        override fun shouldAcknowledge(now: Instant): Boolean = true
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = false
    }
}
