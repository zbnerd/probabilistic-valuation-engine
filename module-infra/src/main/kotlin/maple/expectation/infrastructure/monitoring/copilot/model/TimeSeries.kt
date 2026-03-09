package maple.expectation.infrastructure.monitoring.copilot.model

/**
 * Time series data for a metric
 *
 * @param label Metric label/name
 * @param points Data points in the series
 */
data class TimeSeries(
    val label: String,
    val points: List<MetricPoint> = emptyList(),
)
