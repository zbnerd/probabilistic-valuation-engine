package maple.synchronizer.state

import java.time.Instant

sealed class ChunkExecutionStatus(val name: String) {
    abstract fun isTerminal(): Boolean
    abstract fun shouldAckSkip(now: Instant): Boolean
    abstract fun shouldPreserveKafkaRedelivery(now: Instant): Boolean
    fun isTerminalSkip(): Boolean = this is Succeeded || this is FailedTerminal

    companion object {
        const val PENDING_NAME: String = "PENDING"
        const val PROCESSING_NAME: String = "PROCESSING"
        const val SUCCEEDED_NAME: String = "SUCCEEDED"
        const val FAILED_RETRYABLE_NAME: String = "FAILED_RETRYABLE"
        const val FAILED_TERMINAL_NAME: String = "FAILED_TERMINAL"

        fun fromName(s: String): ChunkExecutionStatus = when (s) {
            PROCESSING_NAME -> Processing
            SUCCEEDED_NAME -> Succeeded
            FAILED_RETRYABLE_NAME -> FailedRetryable(null)
            FAILED_TERMINAL_NAME -> FailedTerminal(null)
            else -> throw IllegalArgumentException("Unknown ChunkExecutionStatus name: $s")
        }
    }

    /** Singleton — use `is Processing` checks, not `===`. */
    object Processing : ChunkExecutionStatus(PROCESSING_NAME) {
        override fun isTerminal(): Boolean = false
        override fun shouldAckSkip(now: Instant): Boolean = false
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = false
        /** True when the lease has expired or was never set — this chunk is reclaimable. */
        fun isReclaimed(leaseUntil: Instant?, now: Instant): Boolean =
            leaseUntil?.isAfter(now) != true
    }

    /** Singleton — use `is Succeeded` checks, not `===`. */
    object Succeeded : ChunkExecutionStatus(SUCCEEDED_NAME) {
        override fun isTerminal(): Boolean = true
        override fun shouldAckSkip(now: Instant): Boolean = true
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = false
    }

    data class FailedRetryable(val nextRetryAt: Instant?) : ChunkExecutionStatus(FAILED_RETRYABLE_NAME) {
        override fun isTerminal(): Boolean = false
        override fun shouldAckSkip(now: Instant): Boolean = nextRetryAt?.isAfter(now) != true
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = nextRetryAt?.isAfter(now) == true
    }

    data class FailedTerminal(val reason: String?) : ChunkExecutionStatus(FAILED_TERMINAL_NAME) {
        override fun isTerminal(): Boolean = true
        override fun shouldAckSkip(now: Instant): Boolean = true
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = false
    }
}
