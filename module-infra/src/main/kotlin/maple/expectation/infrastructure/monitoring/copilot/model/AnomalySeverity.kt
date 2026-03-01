package maple.expectation.infrastructure.monitoring.copilot.model

/** Anomaly Severity Levels */
enum class AnomalySeverity {
    /** Warning level - exceeds warning threshold */
    WARNING,

    /** Critical level - exceeds critical threshold or extreme Z-score */
    CRITICAL
}
