package maple.expectation.core.port.out

import maple.expectation.core.domain.model.CubeType

/**
 * Policy outbound port for cost calculation.
 *
 * <p>Follows hexagonal architecture pattern:
 * - Core defines the port interface
 * - Infrastructure provides the implementation
 * - Application layer depends only on this port
 */
interface PolicyPort {

    /**
     * Get cube cost for given parameters.
     *
     * @param type Cube type (BLACK, RED, ADDITIONAL)
     * @param level Equipment level
     * @param grade Potential grade (Korean: "레어", "에픽", "유니크", "레전드리")
     * @return Cost for single cube use
     * @throws IllegalArgumentException if parameters are invalid
     * @throws IllegalStateException if cube type is unknown
     */
    fun getCubeCost(type: CubeType, level: Int, grade: String): Long
}
