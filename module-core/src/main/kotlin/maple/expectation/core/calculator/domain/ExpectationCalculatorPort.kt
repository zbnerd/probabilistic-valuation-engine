package maple.expectation.core.calculator.domain

import java.util.Optional

/**
 * Core Port: 강화 기대값 계산 표준 인터페이스
 *
 * Port-Based Architecture (ADR-004): module-core가 제공하는 인터페이스
 * module-app 및 module-infra가 이 인터페이스를 구현/의존
 *
 * @see maple.expectation.service.v2.calculator.ExpectationCalculator 기존 V2 인터페이스
 */
interface ExpectationCalculatorPort {
    /**
     * 최종 소모 비용 합산
     * @return 기대 비용 (메소 단위)
     */
    fun calculateCost(): Long

    /**
     * 적용된 강화 경로 문자열 반환
     * @return 강화 경로 (예: "무기 > 블랙큐브(윗잠) > 스타포스")
     */
    fun getEnhancePath(): String

    /**
     * 기대 시도 횟수 (기하분포 기반)
     * @return 기대 시도 횟수 (없으면 Optional.empty())
     */
    fun getTrials(): Optional<Long>
}
