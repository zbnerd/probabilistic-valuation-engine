package maple.expectation.core.calculator.port

import java.math.BigDecimal

/**
 * Outbound Port: 큐브 비용 계산 인터페이스
 *
 * Port-Based Architecture (ADR-004):
 * - Core 도메인이 필요로 하는 큐브 비용 계산 기능을 정의
 * - module-infra가 이 인터페이스를 구현
 * - 의존성 방향: app → core ← infra
 */
interface CubeCostPort {
    /**
     * 큐브 비용 계산
     * @param cubeType 큐브 타입 (BLACK, RED, ADDITIONAL)
     * @param level 아이템 레벨
     * @param grade 목표 등급
     * @return 큐브 비용 (메소)
     */
    fun calculateCubeCost(cubeType: String, level: Int, grade: String): BigDecimal

    /**
     * 큐브 시도 횟수 조회 (기하분포 기반)
     * @param cubeType 큐브 타입
     * @param successRate 성공률
     * @return 기대 시도 횟수
     */
    fun getExpectedTrials(cubeType: String, successRate: Double): BigDecimal
}
