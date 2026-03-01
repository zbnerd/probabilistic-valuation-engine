package maple.expectation.infrastructure.monitoring.copilot.model

/**
 * Action to take for anomaly mitigation
 *
 * @param action Action type (e.g., "notify", "scale", "restart")
 * @param params Action parameters
 * @param risk Risk level of this action
 * @param expectedImpact Expected impact description
 */
data class Action(
    val action: String,
    val params: Map<String, Any> = emptyMap(),
    val risk: String? = null,
    val expectedImpact: String? = null
)
