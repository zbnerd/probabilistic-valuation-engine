package maple.expectation.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Bulk Load Configuration Properties for Issue #611
 *
 * <h3>Configuration</h3>
 *
 * <pre>
 * bulk:
 *   enabled: false
 *   csv-path: classpath:data/userIgn_List.csv
 *   checkpoint-path: ./checkpoint.json
 *   failed-path: ./failed.csv
 *   batch:
 *     initial-size: 100
 *     min-size: 10
 *     max-size: 200
 *   delay:
 *     initial-ms: 100
 *     min-ms: 50
 *     max-ms: 5000
 *   semaphore:
 *     permits: 100
 * </pre>
 *
 * @property enabled Enable/disable bulk load feature
 * @property csvPath Path to CSV file containing user data
 * @property checkpointPath Path to checkpoint file for recovery
 * @property failedPath Path to failed records CSV
 * @property batch Batch size configuration
 * @property delay Delay configuration for rate limiting
 * @property semaphore Semaphore configuration for concurrency control
 */
@Validated
@ConfigurationProperties(prefix = "bulk")
data class BulkLoadProperties(
    val enabled: Boolean = false,
    val csvPath: String = "classpath:data/userIgn_List.csv",
    val checkpointPath: String = "./checkpoint.json",
    val failedPath: String = "./failed.csv",
    val batch: BatchConfig = BatchConfig(),
    val delay: DelayConfig = DelayConfig(),
    val semaphore: SemaphoreConfig = SemaphoreConfig(),
) {
    /**
     * Batch size configuration
     *
     * @property initialSize Initial batch size (100)
     * @property minSize Minimum batch size (10)
     * @property maxSize Maximum batch size (200)
     */
    data class BatchConfig(
        val initialSize: Int = 100,
        val minSize: Int = 10,
        val maxSize: Int = 200,
    )

    /**
     * Delay configuration for adaptive rate limiting
     *
     * @property initialMs Initial delay in milliseconds (100)
     * @property minMs Minimum delay in milliseconds (50)
     * @property maxMs Maximum delay in milliseconds (5000)
     */
    data class DelayConfig(
        val initialMs: Long = 100,
        val minMs: Long = 50,
        val maxMs: Long = 5000,
    )

    /**
     * Semaphore configuration for concurrency control
     *
     * @property permits Number of concurrent permits (100)
     */
    data class SemaphoreConfig(
        val permits: Int = 100,
    )

    companion object {
        /**
         * Factory method for default configuration
         *
         * <p>Used in tests or when default settings are required
         */
        fun defaults() = BulkLoadProperties()
    }
}
