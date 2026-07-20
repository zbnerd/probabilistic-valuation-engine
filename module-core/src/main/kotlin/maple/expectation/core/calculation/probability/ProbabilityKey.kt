package maple.expectation.core.calculation.probability

import maple.expectation.core.domain.model.CubeType

data class ProbabilityKey(
    val cubeType: CubeType,
    val level: Int,
    val part: String,
    val grade: String,
    val slot: Int,
) {
    init {
        require(level >= 0) { "level must not be negative: $level" }
        require(part.isNotBlank()) { "part must not be blank" }
        require(grade.isNotBlank()) { "grade must not be blank" }
        require(slot in 1..3) { "slot must be in 1..3: $slot" }
    }
}
