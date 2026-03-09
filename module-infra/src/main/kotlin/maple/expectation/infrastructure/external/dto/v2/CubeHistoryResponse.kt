package maple.expectation.infrastructure.external.dto.v2

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Nexon API 큐브 사용 결과 응답 DTO
 *
 * <p>Endpoint: GET /maplestory/v1/history/cube
 *
 * <p>큐브 사용 내역 조회 (확률 정보 조회)
 *
 * @see <a href="https://openapi.nexon.com/ko/game/maplestory/?id=17">Nexon Open API</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CubeHistoryResponse(
    /** 큐브 사용 결과 리스트 */
    @JsonProperty("cube_history")
    val cubeHistory: List<CubeHistory>? = null,
) {

    /**
     * 큐브 사용 결과 상세 정보
     *
     * <p>개별 큐브 사용 결과를 나타내는 내부 클래스
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CubeHistory(
        /** 대상 장비 이름 (예: "갈색 가죽 모자", "파란 칠부바지") */
        @JsonProperty("target_item")
        val targetItem: String? = null,

        /** 잠재능력 등급 (레어, 에픽, 레전드리) */
        @JsonProperty("potential_option_grade")
        val potentialOptionGrade: String? = null,

        /** 큐브 사용 후 잠재능력 옵션 배열 (3개 옵션) */
        @JsonProperty("after_potential_option")
        val afterPotentialOption: List<PotentialOption>? = null,
    )

    /**
     * 잠재능력 옵션 상세 정보
     *
     * <p>각 잠재능력 슬롯의 옵션 값
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PotentialOption(
        /** 옵션 값 (예: "STR : +2%", "DEX : +2%") */
        @JsonProperty("value")
        val value: String? = null,
    )
}
