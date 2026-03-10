package maple.expectation.infrastructure.monitoring.copilot.model

/**
 * Severity mapping for signal thresholds
 *
 * @param warnThreshold Warning threshold value
 * @param critThreshold Critical threshold value
 * @param comparator Comparison operator (e.g., ">", "<", ">=", "<=")
 */
data class SeverityMapping(
    val warnThreshold: Double? = null,
    val critThreshold: Double? = null,
    val comparator: String? = null,
)
