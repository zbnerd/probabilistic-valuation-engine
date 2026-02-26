package maple.expectation.infrastructure.external.dto.v2

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 기대값 계산 결과 응답 DTO
 *
 * <p>Issue #158: Zero-Waste 정책 적용
 *
 * <ul>
 *   <li>@JsonInclude(NON_EMPTY): null/빈 값 제외하여 5KB 압박 완화
 *   <li>NON_DEFAULT 금지: 0이 의미 있는 값일 수 있음
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class TotalExpectationResponse(
    val userIgn: String? = null,
    val totalCost: Long = 0, // 총 기대 비용 (메소)
    val totalCostText: String? = null, // "5,300억" 처럼 보기 좋게 (선택)
    val items: List<ItemExpectation>? = null // 각 아이템별 상세 영수증
) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    data class ItemExpectation(
        val part: String? = null, // 부위 (모자)
        val itemName: String? = null, // 이름 (에테르넬...)
        val potential: String? = null, // 잠재 옵션 (STR 12% | 9% | 9%)
        val expectedCost: Long = 0, // 이 아이템 하나 만드는 비용
        val expectedCostText: String? = null, // "80억" (선택)
        val expectedCount: Long = 0 // 기대 횟수
    )
}
