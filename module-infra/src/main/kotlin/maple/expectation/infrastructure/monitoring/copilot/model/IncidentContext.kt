package maple.expectation.infrastructure.monitoring.copilot.model

/**
 * Incident context for anomaly analysis
 */
data class IncidentContext(
    val incidentId: String,
    val summary: String? = null,
    val anomalies: List<AnomalyEvent> = emptyList(),
    val evidence: List<Any> = emptyList(), // Supports both EvidenceItem and RichEvidence
    val metadata: Map<String, Any> = emptyMap()
)
