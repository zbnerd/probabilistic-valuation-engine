package maple.expectation.infrastructure.monitoring.copilot.model

import java.time.Instant

/**
 * Rich evidence with PromQL evaluation results.
 *
 * @property signalId Signal identifier
 * @property signalName Signal display name
 * @property promql PromQL query used
 * @property currentValue Current metric value
 * @property baselineValue Baseline metric value (5 min ago)
 * @property deviationPercent Deviation from baseline (%)
 * @property evaluatedAt Query evaluation timestamp
 */
data class RichEvidence(
    val signalId: String,
    val signalName: String,
    val promql: String,
    val currentValue: Double,
    val baselineValue: Double,
    val deviationPercent: Double,
    val evaluatedAt: Instant
) {
  /**
   * Format deviation as human-readable string. Example: "+25.3%" (increase) or "-12.8%" (decrease)
   */
  fun formattedDeviation(): String {
    val sign = if (deviationPercent >= 0) "+" else ""
    return String.format("%s%.2f%%", sign, deviationPercent)
  }

  /**
   * Get severity direction based on deviation.
   *
   * @return "INCREASE" if positive, "DECREASE" if negative
   */
  fun deviationDirection(): String {
    return if (deviationPercent >= 0) "INCREASE" else "DECREASE"
  }
}
