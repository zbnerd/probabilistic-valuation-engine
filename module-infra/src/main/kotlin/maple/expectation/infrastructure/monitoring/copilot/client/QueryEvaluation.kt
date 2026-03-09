package maple.expectation.infrastructure.monitoring.copilot.client

import java.time.Instant

/**
 * Result of evaluating a PromQL instant query.
 *
 * @property promql The query that was evaluated
 * @property value The scalar result value
 * @property timestamp The query evaluation timestamp
 */
data class QueryEvaluation(
    val promql: String,
    val value: Double,
    val timestamp: Instant,
) {
    /**
     * Calculate deviation from baseline as percentage.
     *
     * @param baseline Baseline value to compare against
     * @return Deviation percentage (positive = increase, negative = decrease)
     */
    fun deviationFrom(baseline: Double): Double {
        if (baseline == 0.0) {
            return if (value > 0) 100.0 else 0.0
        }
        return ((value - baseline) / baseline) * 100.0
    }
}
