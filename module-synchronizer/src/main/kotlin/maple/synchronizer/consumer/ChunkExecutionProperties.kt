package maple.synchronizer.consumer

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("chunk-execution")
data class ChunkExecutionProperties(
    /** Processing lease in seconds. 600 = 10 min — long enough for big chunks, short enough to reclaim stuck workers. */
    val processingTimeoutSeconds: Long = 600,
    val retry: Retry = Retry(),
) {
    data class Retry(
        /** Base backoff for retryable failures. 60 s prevents tight retry loops under sustained upstream errors. */
        val baseBackoffSeconds: Long = 60,
        /** Max attempts for transient failures before terminal. 5 attempts × 60 s base ≈ 30 min of retry window. */
        val maxAttempts: Int = 5,
        /** Max attempts for artifact-missing failures. Lower than [maxAttempts] because missing files rarely appear. */
        val artifactMissingMaxAttempts: Int = 2,
    )

    val processingTimeout: Duration get() = Duration.ofSeconds(processingTimeoutSeconds)
    val retryBaseBackoff: Duration get() = Duration.ofSeconds(retry.baseBackoffSeconds)
}
