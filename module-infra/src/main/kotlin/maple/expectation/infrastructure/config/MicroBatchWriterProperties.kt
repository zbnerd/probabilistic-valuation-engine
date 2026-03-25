package maple.expectation.infrastructure.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.validation.annotation.Validated

/**
 * Micro-Batch Writer Configuration (Issue #617 US-003)
 *
 * <h3>Properties</h3>
 * <ul>
 *   <li>flushSize: Buffer size threshold for automatic flush (default: 500)</li>
 *   <li>flushIntervalMs: Time interval for scheduled flush (default: 50ms)</li>
 * </ul>
 *
 * <h3>application.yml configuration:</h3>
 * <pre>
 * micro-batch-writer:
 *   flush-size: 500
 *   flush-interval-ms: 50
 * </pre>
 *
 * @property flushSize Buffer size threshold for size-triggered flush (default: 500, min: 100, max: 5000)
 * @property flushIntervalMs Time interval for time-triggered flush in milliseconds (default: 50, min: 10, max: 500)
 */
@Validated
@ConfigurationProperties(prefix = "micro-batch-writer")
data class MicroBatchWriterProperties(
    /**
     * Buffer size threshold for automatic flush.
     *
     * When buffer.size >= flushSize, automatic flush is triggered.
     */
    @DefaultValue("500") @Min(100) @Max(5000)
    val flushSize: Int = 500,

    /**
     * Time interval for scheduled flush in milliseconds.
     *
     * ScheduledExecutorService flushes buffer every flushIntervalMs,
     * even if buffer size < flushSize.
     */
    @DefaultValue("50") @Min(10) @Max(500)
    val flushIntervalMs: Long = 50,
)
