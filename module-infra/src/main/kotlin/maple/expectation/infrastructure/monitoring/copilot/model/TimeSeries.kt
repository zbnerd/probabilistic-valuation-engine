package maple.expectation.infrastructure.monitoring.copilot.model

data class TimeSeries(
    val label: String,
    val points: List<MetricPoint>
)
