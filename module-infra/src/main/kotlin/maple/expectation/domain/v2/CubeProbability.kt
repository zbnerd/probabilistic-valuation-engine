package maple.expectation.domain.v2

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 큐브 확률 데이터 도메인 모델
 */
data class CubeProbability(
    @JsonProperty("cube_type")
    val cubeType: CubeType,

    @JsonProperty("option")
    val optionName: String,

    @JsonProperty("rate")
    val rate: Double,

    @JsonProperty("slot")
    val slot: Int,

    @JsonProperty("potential_option_grade")
    val grade: String,

    @JsonProperty("base_equipment_level")
    val level: Int,

    @JsonProperty("item_equipment_slot")
    val part: String,
)
