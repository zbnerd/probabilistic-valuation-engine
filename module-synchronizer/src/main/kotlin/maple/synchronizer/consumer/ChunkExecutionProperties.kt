package maple.synchronizer.consumer

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("chunk-execution")
data class ChunkExecutionProperties(
    val processingTimeoutSeconds: Long = 600,
    val retry: Retry = Retry(),
) {
    data class Retry(
        val baseBackoffSeconds: Long = 60,
        val maxAttempts: Int = 5,
        val artifactMissingMaxAttempts: Int = 2,
    )

    val processingTimeout: Duration get() = Duration.ofSeconds(processingTimeoutSeconds)
    val retryBaseBackoff: Duration get() = Duration.ofSeconds(retry.baseBackoffSeconds)
}
