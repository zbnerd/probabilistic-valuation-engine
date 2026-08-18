package maple.expectation.core.calculation

import maple.expectation.core.starforce.domain.NoljangProbabilityCalculator

data class ValuationInput(
    val itemName: String,
    val part: String,
    val equipmentPart: String,
    val itemLevel: Int,
    val currentStar: Int,
    val targetStar: Int,
    val noljang: Boolean,
    val potentialGrade: String?,
    val potentialOptions: List<String>,
    val additionalGrade: String?,
    val additionalOptions: List<String>,
) {
    val normalizedTargetStar: Int
        get() = if (noljang) {
            minOf(targetStar, NoljangProbabilityCalculator.MAX_NOLJANG_STAR)
        } else {
            targetStar
        }
}
