package maple.expectation.infrastructure.buffer

import java.time.LocalDateTime

/**
 * Expectation Write-Behind 버퍼용 DTO (#266, ADR-005)
 *
 * **5-Agent Council 합의**
 * - Purple (Auditor): Record로 immutability 보장
 * - Green (Performance): Double로 변경하여 성능 최적화 (2026-03-23)
 *
 * **용도**
 * V4 기대값 계산 결과를 메모리 버퍼에 저장하고, 스케줄러가 배치로 DB에 동기화할 때 사용됩니다.
 *
 * @param characterId 캐릭터 ID
 * @param presetNo 프리셋 번호 (1, 2, 3)
 * @param totalExpectedCost 총 기대 비용
 * @param blackCubeCost 블랙큐브 비용
 * @param redCubeCost 레드큐브 비용
 * @param additionalCubeCost 에디셔널큐브 비용
 * @param starforceCost 스타포스 비용
 * @param createdAt 생성 시각
 */
data class ExpectationWriteTask(
    val characterId: Long,
    val presetNo: Int,
    val totalExpectedCost: Double,
    val blackCubeCost: Double,
    val redCubeCost: Double,
    val additionalCubeCost: Double,
    val starforceCost: Double,
    val createdAt: LocalDateTime,
) {
    /**
     * 버퍼 키 생성 (중복 방지용)
     * 동일 캐릭터의 동일 프리셋은 Latest-wins 전략으로 덮어쓰기
     * @return "{characterId}:{presetNo}" 형식의 키
     */
    fun key(): String = "$characterId:$presetNo"
}
