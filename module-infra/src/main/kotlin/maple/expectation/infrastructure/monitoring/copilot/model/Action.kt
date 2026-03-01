package maple.expectation.infrastructure.monitoring.copilot.model

data class Action(
    val action: String,
    val params: Map<String, Any>,
    val risk: String,
    val expectedImpact: String
)
