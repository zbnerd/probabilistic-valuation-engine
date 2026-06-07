package maple.synchronizer.state

import java.time.Instant

sealed class FailureDecision {
    abstract val attemptCount: Int
    abstract val reason: String

    data class Retryable(
        override val attemptCount: Int,
        val nextRetryAt: Instant,
    ) : FailureDecision() {
        override val reason: String = RETRYABLE_FAILURE
    }

    data class Terminal(
        override val attemptCount: Int,
        val terminalReason: String,
    ) : FailureDecision() {
        override val reason: String = terminalReason
    }

    private companion object {
        private const val RETRYABLE_FAILURE = "RETRYABLE_FAILURE"
    }
}
