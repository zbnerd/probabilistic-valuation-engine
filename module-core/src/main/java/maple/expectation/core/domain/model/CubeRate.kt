package maple.expectation.core.domain.model

/**
 * Cube probability rate domain model.
 *
 * Represents the probability rate for a specific option appearing on a cube.
 *
 * Pure domain model - no external dependencies.
 *
 * @property cubeType the type of cube (BLACK, RED, ADDITIONAL)
 * @property optionName the name of the potential option
 * @property rate the probability rate (0.0 to 1.0)
 * @property slot the slot number (1, 2, or 3)
 * @property grade the potential option grade (RARE, EPIC, LEGENDARY)
 * @property level the base equipment level
 * @property part the equipment part (헤어, 아이언, etc.)
 */
data class CubeRate(
    val cubeType: CubeType,
    @get:JvmName("optionName") val optionName: String,
    @get:JvmName("rate") val rate: Double,
    val slot: Int,
    val grade: String,
    val level: Int,
    val part: String
) {
    init {
        require(rate in 0.0..1.0) { "rate must be between 0.0 and 1.0, got: $rate" }
        require(slot in 1..3) { "slot must be 1, 2, or 3, got: $slot" }
        require(level >= 0) { "level cannot be negative, got: $level" }
        require(optionName.isNotBlank()) { "optionName cannot be null or blank" }
        require(grade.isNotBlank()) { "grade cannot be null or blank" }
        require(part.isNotBlank()) { "part cannot be null or blank" }
    }

    companion object {
        /**
         * Create a cube rate with minimal required fields.
         */
        @JvmStatic
        fun of(cubeType: CubeType, optionName: String, rate: Double): CubeRate {
            return CubeRate(cubeType, optionName, rate, 1, "EPIC", 200, "모자")
        }
    }
}
