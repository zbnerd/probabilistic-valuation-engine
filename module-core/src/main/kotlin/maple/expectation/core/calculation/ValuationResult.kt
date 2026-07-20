package maple.expectation.core.calculation

import maple.expectation.core.calculation.probability.ProbabilityTableVersion

data class ValuationResult(
    val costs: ComponentCosts,
    val trials: ComponentTrials,
    val enhancePath: String,
    val tableVersion: ProbabilityTableVersion,
    val logicVersion: String,
)
