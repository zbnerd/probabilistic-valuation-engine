package maple.expectation.infrastructure.monitoring.copilot.model

data class Hypothesis(
    val cause: String,
    val confidence: Double,
    val evidence: String
)
