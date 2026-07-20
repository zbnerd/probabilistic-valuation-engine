package maple.calculator.probability

import com.fasterxml.jackson.annotation.JsonProperty
import maple.expectation.core.domain.model.CubeType

data class CsvProbabilityRow(
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
    @JsonProperty("cube_type")
    val cubeType: CubeType,
)
