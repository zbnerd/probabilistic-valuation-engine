package maple.expectation.infrastructure.monitoring.copilot.model

import java.time.Instant

/**
 * Rich evidence with PromQL evaluation results.
 *
 * @param signalId Signal identifier
 * @param signalName Signal display name
 * @param promql PromQL query used
 * @param currentValue Current metric value
 * @param baselineValue Baseline metric value (5 min ago)
 * @param deviationPercent Deviation from baseline (%)
 * @param evaluatedAt Query evaluation timestamp
 */
data class RichEvidence(
    val signalId: String,
    val signalName: String? = null,
    val promql: String? = null,
    val currentValue: Double = 0.0,
    val baselineValue: Double = 0.0,
    val deviationPercent: Double = 0.0,
    val evaluatedAt: Instant? = null,
) {
    /**
     * Format deviation as human-readable string.
     * Example: "+25.3%" (increase) or "-12.8%" (decrease)
     */
    fun formattedDeviation(): String {
        val sign = if (deviationPercent >= 0) "+" else ""
        return String.format("%s%.2f%%", sign, deviationPercent)
    }

    /**
     * Get severity direction based on deviation.
     * @return "INCREASE" if positive, "DECREASE" if negative
     */
    fun deviationDirection(): String = if (deviationPercent >= 0) "INCREASE" else "DECREASE"
}
