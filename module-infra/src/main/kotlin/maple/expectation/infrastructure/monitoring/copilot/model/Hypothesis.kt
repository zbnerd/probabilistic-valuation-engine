package maple.expectation.infrastructure.monitoring.copilot.model

/**
 * Hypothesis for root cause analysis
 */
data class Hypothesis(
    val cause: String,
    val confidence: Double? = null,
    val evidence: String? = null
)
