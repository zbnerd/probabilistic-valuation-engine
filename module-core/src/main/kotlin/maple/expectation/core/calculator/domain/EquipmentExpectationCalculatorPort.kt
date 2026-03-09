package maple.expectation.core.calculator.domain

import java.math.BigDecimal
import java.util.Optional

/**
 * Core Port: V4 장비 기대값 계산기 인터페이스
 *
 * Port-Based Architecture (ADR-004): module-core가 제공하는 인터페이스
 *
 * 5-Agent Council 합의사항:
 * - 🟣 Purple (Auditor): BigDecimal 필수 - 정밀 계산
 * - 🔵 Blue (Architect): OCP 준수 - 기존 ExpectationCalculator 유지
 *
 * 기존 ExpectationCalculatorPort와의 차이:
 * - calculateCost() → BigDecimal (long에서 변경)
 * - getTrials() → BigDecimal (정밀 기대값 계산)
 * - 새로운 메서드: getDetailedCosts() - 비용 상세 분류
 *
 * @see maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator 기존 V4 인터페이스
 */
interface EquipmentExpectationCalculatorPort {

    /**
     * 최종 소모 비용 합산 (BigDecimal)
     *
     * Purple Agent 요구사항: 정밀 계산을 위해 BigDecimal 사용
     * @return 기대 비용 (메소 단위)
     */
    fun calculateCost(): BigDecimal

    /**
     * 적용된 강화 경로 문자열 반환
     * @return 강화 경로 (예: "무기 > 블랙큐브(윗잠) > 레드큐브(윗잠) > 에디셔널(아랫잠) > 스타포스")
     */
    fun getEnhancePath(): String

    /**
     * 기대 시도 횟수 (기하분포 기반)
     * @return 기대 시도 횟수 (없으면 Optional.empty())
     */
    fun getTrials(): Optional<BigDecimal>

    /**
     * 비용 상세 분류
     *
     * V4 API에서 항목별 비용 분류를 위해 사용
     * @return 비용 상세 (블랙큐브, 레드큐브, 에디셔널, 스타포스 등)
     */
    fun getDetailedCosts(): CostBreakdown

    /**
     * 비용 상세 분류 Record
     *
     * trials는 기대 시도 횟수로, 정수로 변환하여 사용합니다.
     */
    data class CostBreakdown(
        val blackCubeCost: BigDecimal = BigDecimal.ZERO,
        val redCubeCost: BigDecimal = BigDecimal.ZERO,
        val additionalCubeCost: BigDecimal = BigDecimal.ZERO,
        val starforceCost: BigDecimal = BigDecimal.ZERO,
        val blackCubeTrials: BigDecimal = BigDecimal.ZERO,
        val redCubeTrials: BigDecimal = BigDecimal.ZERO,
        val additionalCubeTrials: BigDecimal = BigDecimal.ZERO,
    ) {
        companion object {
            @JvmStatic
            fun empty() = CostBreakdown()
        }

        fun total() = blackCubeCost.add(redCubeCost).add(additionalCubeCost).add(starforceCost)

        fun withBlackCube(cost: BigDecimal) = copy(blackCubeCost = cost)

        fun withBlackCube(cost: BigDecimal, trials: BigDecimal) = copy(blackCubeCost = cost, blackCubeTrials = trials)

        fun withRedCube(cost: BigDecimal) = copy(redCubeCost = cost)

        fun withRedCube(cost: BigDecimal, trials: BigDecimal) = copy(redCubeCost = cost, redCubeTrials = trials)

        fun withAdditionalCube(cost: BigDecimal) = copy(additionalCubeCost = cost)

        fun withAdditionalCube(cost: BigDecimal, trials: BigDecimal) = copy(additionalCubeCost = cost, additionalCubeTrials = trials)

        fun withStarforce(cost: BigDecimal) = copy(starforceCost = cost)
    }
}
