package maple.expectation.infrastructure.monitoring.copilot.model

/**
 * Evidence item for incident analysis
 */
data class EvidenceItem(
    val type: String,
    val title: String? = null,
    val body: String? = null
)
