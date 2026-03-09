package maple.expectation.infrastructure.monitoring.copilot.model

import java.time.Instant

/**
 * Single metric data point
 *
 * @param epochMillis Timestamp in milliseconds since epoch
 * @param value Metric value
 */
data class MetricPoint(
    val epochMillis: Long,
    val value: Double,
) {
    /**
     * Get timestamp as Instant
     */
    fun toInstant(): Instant = Instant.ofEpochMilli(epochMillis)

    companion object {
        /**
         * Create MetricPoint from Instant
         */
        fun fromInstant(timestamp: Instant, value: Double): MetricPoint = MetricPoint(timestamp.toEpochMilli(), value)
    }
}
