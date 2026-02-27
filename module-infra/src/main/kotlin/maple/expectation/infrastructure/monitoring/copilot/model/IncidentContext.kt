package maple.expectation.infrastructure.monitoring.copilot.model

data class IncidentContext(
    val incidentId: String,
    val summary: String,
    val anomalies: List<AnomalyEvent>,
    val evidence: List<Any>,
    // Supports both EvidenceItem and RichEvidence
    val metadata: Map<String, Any>
)
