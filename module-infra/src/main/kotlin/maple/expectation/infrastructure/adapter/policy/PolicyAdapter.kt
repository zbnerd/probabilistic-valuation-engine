package maple.expectation.infrastructure.adapter.policy

import maple.expectation.core.domain.model.CubeType
import maple.expectation.core.policy.TableBasedCostStrategy
import maple.expectation.core.port.out.PolicyPort
import org.springframework.stereotype.Component

/**
 * Policy infrastructure adapter.
 *
 * <p>Implements PolicyPort using TableBasedCostStrategy from core.
 * Bridges module-app to module-core policy domain.
 */
@Component
class PolicyAdapter : PolicyPort {

    private val costStrategy = TableBasedCostStrategy()

    override fun getCubeCost(type: CubeType, level: Int, grade: String): Long = costStrategy.calculateCost(type, level, grade)
}
