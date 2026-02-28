package maple.expectation.core.calculator.domain

import java.util.Optional

/**
 * Core Domain: 강화가 시작되는 원본 아이템
 *
 * Decorator Pattern (GoF) - Concrete Component
 * Decorator 체인의 시작점. 기본 아이템 자체의 비용은 0입니다.
 *
 * Port-Based Architecture (ADR-004):
 * - module-core의 순수 도메인 모델
 * - Spring @Component 제거 (인프라 계층으로 이관)
 * - 불변 데이터 클래스 (data class)
 *
 * @see ExpectationCalculatorPort 대상 인터페이스
 * @see EnhanceDecorator 추상 장식자
 */
data class BaseItem(
    private val itemName: String
) : ExpectationCalculatorPort {

    override fun calculateCost(): Long = 0 // 기본 아이템 자체의 소모 비용은 0

    override fun getEnhancePath(): String = itemName

    override fun getTrials(): Optional<Long> = Optional.of(0L)
}
