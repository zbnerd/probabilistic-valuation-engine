package maple.expectation.core.calculator.domain

import java.math.BigDecimal
import java.util.Optional

/**
 * Core Domain: V4 기본 장비 아이템
 *
 * Decorator Pattern (GoF) - Concrete Component
 * Decorator 체인의 시작점. 기본 아이템 자체의 비용은 0입니다.
 *
 * Port-Based Architecture (ADR-004):
 * - module-core의 순수 도메인 모델
 * - Spring @Component 제거
 * - 불변 데이터 클래스 (data class)
 *
 * @see EquipmentExpectationCalculatorPort 대상 인터페이스
 * @see EquipmentEnhanceDecorator 추상 장식자
 */
data class BaseEquipmentItem(
    private val itemName: String,
    private val itemLevel: Int,
    private val currentStar: Int
) : EquipmentExpectationCalculatorPort {

    override fun calculateCost(): BigDecimal = BigDecimal.ZERO

    override fun getEnhancePath(): String = itemName

    override fun getTrials(): Optional<BigDecimal> = Optional.of(BigDecimal.ZERO)

    override fun getDetailedCosts(): EquipmentExpectationCalculatorPort.CostBreakdown =
        EquipmentExpectationCalculatorPort.CostBreakdown.empty()

    fun getItemLevel(): Int = itemLevel

    fun getCurrentStar(): Int = currentStar
}
