package maple.expectation.core.calculator.domain

import java.math.BigDecimal
import java.util.Optional

/**
 * Core Domain: V4 장비 강화 데코레이터 추상 클래스
 *
 * Decorator Pattern (GoF): 기존 계산기를 감싸서 추가 강화 비용을 누적
 *
 * SOLID - OCP 준수:
 * 새로운 강화 타입 추가 시 이 클래스를 상속하여 확장
 *
 * Port-Based Architecture (ADR-004):
 * - module-core의 순수 도메인 로직
 * - 인프라 의존성 제거
 * - 포트 인터페이스를 통한 의존성 역전
 *
 * @see EquipmentExpectationCalculatorPort 대상 인터페이스
 */
abstract class EquipmentEnhanceDecorator(
    protected val target: EquipmentExpectationCalculatorPort,
) : EquipmentExpectationCalculatorPort {

    override fun calculateCost(): BigDecimal = target.calculateCost()

    override fun getEnhancePath(): String = target.getEnhancePath()

    override fun getTrials(): Optional<BigDecimal> = target.getTrials()

    override fun getDetailedCosts(): EquipmentExpectationCalculatorPort.CostBreakdown = target.getDetailedCosts()
}
