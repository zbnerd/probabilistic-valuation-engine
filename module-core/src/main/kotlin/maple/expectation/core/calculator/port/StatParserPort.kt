package maple.expectation.core.calculator.port

/**
 * Outbound Port: 스탯 파싱 인터페이스
 *
 * Port-Based Architecture (ADR-004):
 * - Core 도메인이 필요로 하는 스탯 파싱 기능을 정의
 * - module-infra가 이 인터페이스를 구현
 * - 의존성 방향: app → core ← infra
 */
interface StatParserPort {
    /**
     * 옵션 문자열에서 수치 파싱
     * @param optionStr 옵션 문자열 (예: "STR +9%", "공격력 +3")
     * @return 파싱된 수치
     */
    fun parseNum(optionStr: String): Int
}
