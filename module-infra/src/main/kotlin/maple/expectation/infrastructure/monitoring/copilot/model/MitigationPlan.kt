package maple.expectation.infrastructure.monitoring.copilot.model

/**
 * Mitigation plan for incident resolution
 */
data class MitigationPlan(
    val hypotheses: List<Hypothesis> = emptyList(),
    val actions: List<Action> = emptyList(),
    val questionsToConfirm: List<String> = emptyList(),
    val riskLevel: String? = null,
    val rollbackPlan: Map<String, Any> = emptyMap()
)
