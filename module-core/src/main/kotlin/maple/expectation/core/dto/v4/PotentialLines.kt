package maple.expectation.core.dto.v4

import maple.expectation.core.domain.model.PotentialGrade

data class PotentialLines(
    val grade: PotentialGrade,
    val line1: PotentialOption?,
    val line2: PotentialOption?,
    val line3: PotentialOption?
) {
    fun asList(): List<String?> = listOf(line1, line2, line3)
}

typealias PotentialOption = String
