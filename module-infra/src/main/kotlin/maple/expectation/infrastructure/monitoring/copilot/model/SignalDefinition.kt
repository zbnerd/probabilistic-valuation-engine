package maple.expectation.infrastructure.monitoring.copilot.model

data class SignalDefinition(
    val id: String,
    val dashboardUid: String,
    val panelTitle: String,
    val datasourceType: String,
    val query: String,
    val legend: String,
    val unit: String,
    val severityMapping: SeverityMapping,
    val sloTag: String,
    val metadata: Map<String, String>
)
