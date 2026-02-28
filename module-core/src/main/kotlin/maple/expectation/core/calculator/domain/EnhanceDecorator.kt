package maple.expectation.core.calculator.domain

/**
 * Core Domain: 다른 계산기를 감싸기 위한 추상 장식자
 *
 * Decorator Pattern (GoF): 기존 계산기에 추가 기능을 동적으로 부여
 *
 * Port-Based Architecture (ADR-004):
 * - module-core의 순수 도메인 로직
 * - 인프라 의존성 제거 (LogicExecutor, Spring 등)
 * - 포트 인터페이스를 통한 의존성 역전
 *
 * @see ExpectationCalculatorPort 대상 인터페이스
 */
abstract class EnhanceDecorator(
    protected val target: ExpectationCalculatorPort
) : ExpectationCalculatorPort {

    override fun calculateCost(): Long = target.calculateCost()

    override fun getEnhancePath(): String = target.getEnhancePath()

    override fun getTrials() = target.getTrials()
}
