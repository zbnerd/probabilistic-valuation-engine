package maple.expectation.core.dto.cube

data class CubeComputeKey(
    val type: String,
    val level: Int,
    val part: String?,
    val grade: String?,
    val targetStatType: String?,
    val minTotal: Int?,
    val enableTailClamp: Boolean,
    val tableVersion: String,
) {
    companion object {
        @JvmStatic
        fun from(input: CubeCalculationInput, type: String, tableVersion: String): CubeComputeKey =
            CubeComputeKey(
                type = type,
                level = input.level,
                part = input.part,
                grade = input.grade,
                targetStatType = input.targetStatType?.name,
                minTotal = input.minTotal,
                enableTailClamp = input.enableTailClamp,
                tableVersion = tableVersion,
            )
    }
}
