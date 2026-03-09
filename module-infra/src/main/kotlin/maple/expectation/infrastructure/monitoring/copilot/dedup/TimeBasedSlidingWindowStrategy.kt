package maple.expectation.infrastructure.monitoring.copilot.dedup

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.monitoring.copilot.client.PrometheusClient
import maple.expectation.infrastructure.monitoring.copilot.model.AnomalyEvent
import maple.expectation.infrastructure.monitoring.copilot.model.SignalDefinition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Stateless time-based sliding window deduplication strategy.
 *
 * <p>Prevents duplicate notifications by querying Prometheus for recent anomalies within the
 * deduplication window. NO in-memory state - completely stateless and scale-out friendly.
 *
 * <h3>Stateless Design</h3>
 *
 * <p>Uses PromQL re-query to check if threshold was exceeded in the sliding window. Prometheus is
 * the single source of truth. Server restarts or multiple instances do not affect deduplication
 * accuracy.
 *
 * <h3>CLAUDE.md Compliance</h3>
 *
 * <ul>
 *   <li>Section 4: Strategy Pattern implementation
 *   <li>Section 12: LogicExecutor pattern for exception handling
 *   <li>Stateless: No server-bound state (scale-out friendly)
 * </ul>
 */
@Component
class TimeBasedSlidingWindowStrategy(
    private val prometheusClient: PrometheusClient,
    private val executor: LogicExecutor,
    @Value("\${monitoring.copilot.dedup-window-minutes:10}")
    private val dedupWindowMinutes: Long,
) : SignalDeduplicationStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(TimeBasedSlidingWindowStrategy::class.java)
    }

    override fun shouldSkip(event: AnomalyEvent, signal: SignalDefinition, currentTimestamp: Long): Boolean = executor.executeOrDefault(
        { checkDuplicateInWindow(event, signal, currentTimestamp) },
        false,
        // Fail open: allow if query fails
        TaskContext.of("SignalDedup", "CheckDuplicate", event.signalId),
    )

    override fun recordDetection(event: AnomalyEvent, currentTimestamp: Long) {
        // NO-OP: Stateless design - no recording needed
        log.debug("[SignalDedup] Stateless mode - recording skipped for: {}", event.signalId)
    }

    override fun cleanup(currentTimestamp: Long) {
        // NO-OP: Stateless design - no cleanup needed
        log.debug("[SignalDedup] Stateless mode - cleanup skipped")
    }

    /**
     * Check if anomaly was already detected in the sliding window using PromQL re-query.
     *
     * <p>This is the CORE of stateless deduplication:
     *
     * <ol>
     *   <li>Query Prometheus for the time window
     *   <li>Check if threshold was exceeded recently
     *   <li>If yes, skip as duplicate
     * </ol>
     */
    private fun checkDuplicateInWindow(
        event: AnomalyEvent,
        signal: SignalDefinition,
        currentTimestamp: Long,
    ): Boolean {
        // Skip if no query defined
        val query = signal.query ?: run {
            log.debug("[SignalDedup] No query defined for signal: {}", event.signalId)
            return false
        }

        val detectedAt = java.time.Instant.ofEpochMilli(currentTimestamp)
        val windowStart = java.time.Instant.ofEpochMilli(currentTimestamp - (dedupWindowMinutes * 60 * 1000))

        // Query Prometheus for historical data in the window
        val timeSeries: List<PrometheusClient.TimeSeries> =
            prometheusClient.queryRange(
                query,
                windowStart,
                detectedAt,
                "1m", // 1-minute resolution
            )

        if (timeSeries.isEmpty()) {
            log.debug("[SignalDedup] No historical data for signal: {}", event.signalId)
            return false
        }

        // Get threshold from signal definition
        val threshold = signal.severityMapping?.warnThreshold ?: 0.0
        val comparator = signal.severityMapping?.comparator ?: ">"

        // Check if any point in the window exceeded threshold
        for (series in timeSeries) {
            for (point in series.values) {
                val value = point.getValueAsDouble()
                val exceeded = exceedsThreshold(value, threshold, comparator)

                if (exceeded) {
                    log.debug(
                        "[SignalDedup] Duplicate detected: {} at {} (value: {}, threshold: {})",
                        event.signalId,
                        point.getTimestampAsInstant(),
                        value,
                        threshold,
                    )
                    return true // Duplicate found
                }
            }
        }

        return false // No duplicate
    }

    /** Check if value exceeds threshold based on comparator. */
    private fun exceedsThreshold(value: Double, threshold: Double, comparator: String?): Boolean {
        val comp = comparator ?: ">"
        return when (comp.trim()) {
            ">", "gt", "greater than" -> value > threshold
            ">=", "gte", "greater than or equal" -> value >= threshold
            "<", "lt", "less than" -> value < threshold
            "<=", "lte", "less than or equal" -> value <= threshold
            else -> value > threshold
        }
    }
}
