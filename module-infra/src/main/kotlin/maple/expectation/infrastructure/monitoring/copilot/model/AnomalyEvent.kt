package maple.expectation.infrastructure.monitoring.copilot.model

/**
 * Anomaly event detected by the monitoring system
 */
data class AnomalyEvent(
    val signalId: String,
    val severity: String,
    val reason: String? = null,
    val detectedAtMillis: Long = System.currentTimeMillis(),
    val currentValue: Double = 0.0,
    val baselineValue: Double? = null
)
