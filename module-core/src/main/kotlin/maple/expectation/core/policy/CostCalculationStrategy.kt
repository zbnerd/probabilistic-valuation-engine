package maple.expectation.core.policy

import maple.expectation.core.domain.model.CubeType

/**
 * Cost calculation strategy interface (Strategy Pattern).
 *
 * <p>OCP (Open-Closed Principle) compliance via Strategy Pattern:
 * - Open for extension: Add new cost calculation strategies
 * - Closed for modification: No need to modify existing code
 *
 * <p>Usage examples:
 * ```kotlin
 * // 1. Default strategy (hardcoded table)
 * val defaultStrategy = TableBasedCostStrategy()
 *
 * // 2. Dynamic strategy (loaded from DB/config)
 * val dynamicStrategy = DynamicCostStrategy(repository)
 *
 * // 3. Cached strategy
 * val cachedStrategy = CachedCostStrategy(delegate)
 * ```
 */
fun interface CostCalculationStrategy {

    /**
     * Calculate cube cost.
     *
     * @param type Cube type (BLACK, RED, ADDITIONAL)
     * @param level Equipment level
     * @param grade Potential grade (Korean: "레어", "에픽", "유니크", "레전드리")
     * @return Cost for single cube use
     * @throws IllegalArgumentException if parameters are invalid
     */
    fun calculateCost(type: CubeType, level: Int, grade: String): Long
}
