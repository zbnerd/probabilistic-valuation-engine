package maple.expectation.infrastructure.monitoring.copilot.model

data class MitigationPlan(
    val hypotheses: List<Hypothesis>,
    val actions: List<Action>,
    val questionsToConfirm: List<String>,
    val riskLevel: String,
    val rollbackPlan: Map<String, Any>
)
