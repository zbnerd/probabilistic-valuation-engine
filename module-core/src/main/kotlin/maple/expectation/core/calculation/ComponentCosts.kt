package maple.expectation.core.calculation

data class ComponentCosts(
    val blackCubeCost: Double?,
    val additionalCubeCost: Double?,
    val starforceCost: Double?,
) {
    val totalCost: Double? = listOfNotNull(
        blackCubeCost,
        additionalCubeCost,
        starforceCost,
    ).takeIf { costs -> costs.isNotEmpty() }?.sum()
}
