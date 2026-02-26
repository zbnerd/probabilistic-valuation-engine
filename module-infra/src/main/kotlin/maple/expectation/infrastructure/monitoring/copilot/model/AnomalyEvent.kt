package maple.expectation.infrastructure.monitoring.copilot.model

data class AnomalyEvent(
    val signalId: String,
    val severity: String,
    val reason: String,
    val detectedAtMillis: Long,
    val currentValue: Double,
    val baselineValue: Double
)
