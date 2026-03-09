package maple.expectation.infrastructure.monitoring.copilot.model

/**
 * Signal definition for monitoring
 *
 * @param id Signal identifier
 * @param dashboardUid Grafana dashboard UID
 * @param panelTitle Panel title
 * @param datasourceType Data source type (e.g., "prometheus")
 * @param query Query string
 * @param legend Legend template
 * @param unit Unit of measurement
 * @param severityMapping Threshold mapping
 * @param sloTag SLO tag
 * @param metadata Additional metadata
 */
data class SignalDefinition(
    val id: String,
    val dashboardUid: String? = null,
    val panelTitle: String? = null,
    val datasourceType: String? = null,
    val query: String? = null,
    val legend: String? = null,
    val unit: String? = null,
    val severityMapping: SeverityMapping? = null,
    val sloTag: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
