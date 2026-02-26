package maple.expectation.infrastructure.monitoring.copilot.model

data class SeverityMapping(
    val warnThreshold: Double,
    val critThreshold: Double,
    val comparator: String
)
