package maple.expectation.core.calculator.port

/**
 * Outbound Port: 큐브 성공률 조회 인터페이스
 *
 * Port-Based Architecture (ADR-004):
 * - Core 도메인이 필요로 하는 큐브 성공률 조회 기능을 정의
 * - module-infra가 이 인터페이스를 구현
 * - 의존성 방향: app → core ← infra
 */
interface CubeRatePort {
    /**
     * 큐브 성공률 조회
     * @param cubeType 큐브 타입
     * @param targetGrade 목표 등급
     * @return 성공률 (0.0 ~ 1.0)
     */
    fun getSuccessRate(cubeType: String, targetGrade: String): Double
}
