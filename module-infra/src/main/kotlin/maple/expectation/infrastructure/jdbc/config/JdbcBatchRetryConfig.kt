package maple.expectation.infrastructure.jdbc.config

import java.time.Duration

/**
 * Configuration for JDBC batch operation retry logic.
 *
 * Provides configurable retry behavior for transient database failures.
 *
 * @see maple.expectation.infrastructure.jdbc.JdbcBatchUpsertRepository
 */
class JdbcBatchRetryConfig(
    /** Maximum number of retry attempts (must be >= 0) */
    val maxRetries: Int,
    /** Initial backoff duration between retries */
    val initialBackoff: Duration,
    /** Multiplier for exponential backoff (must be >= 1.0) */
    val backoffMultiplier: Double,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0: $maxRetries" }
        require(!initialBackoff.isNegative) { "initialBackoff must be positive: $initialBackoff" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0: $backoffMultiplier" }
    }

    /**
     * Calculates the backoff duration for the given retry attempt.
     *
     * @param attempt the retry attempt number (0-based)
     * @return the backoff duration
     */
    fun getBackoffForAttempt(attempt: Int): Duration {
        val millis = (initialBackoff.toMillis() * Math.pow(backoffMultiplier, attempt.toDouble())).toLong()
        return Duration.ofMillis(millis)
    }

    /** Creates a new configuration with a different max retry count. */
    fun withMaxRetries(maxRetries: Int): JdbcBatchRetryConfig = JdbcBatchRetryConfig(maxRetries, this.initialBackoff, this.backoffMultiplier)

    /** Creates a new configuration with a different initial backoff. */
    fun withInitialBackoff(initialBackoff: Duration): JdbcBatchRetryConfig = JdbcBatchRetryConfig(this.maxRetries, initialBackoff, this.backoffMultiplier)

    companion object {
        /** Default retry configuration: 3 retries with exponential backoff. */
        @JvmField
        val DEFAULT = JdbcBatchRetryConfig(3, Duration.ofMillis(100), 2.0)

        /** Creates a retry configuration with no retries. */
        fun noRetry(): JdbcBatchRetryConfig = JdbcBatchRetryConfig(0, Duration.ZERO, 1.0)
    }
}
