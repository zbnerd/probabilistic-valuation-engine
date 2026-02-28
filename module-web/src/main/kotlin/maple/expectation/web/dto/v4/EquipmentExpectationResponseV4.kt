package maple.expectation.web.dto.v4

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * V4 장비 기대값 응답 DTO (#240)
 */
data class EquipmentExpectationResponseV4(
    val userIgn: String,
    val calculatedAt: LocalDateTime,
    val fromCache: Boolean,
    val totalExpectedCost: BigDecimal,
    val totalCostText: String,
    val totalCostBreakdown: CostBreakdownDto,
    val maxPresetNo: Int,
    val presets: List<PresetExpectation>
) {
    /** Java 호환성을 위한 메서드 */
    fun isFromCache(): Boolean = fromCache
    data class PresetExpectation(
        val presetNo: Int,
        val totalExpectedCost: BigDecimal,
        val totalCostText: String,
        val costBreakdown: CostBreakdownDto,
        val items: List<ItemExpectationV4>
    )

    data class ItemExpectationV4(
        val itemName: String,
        val itemIcon: String,
        val itemPart: String,
        val itemLevel: Int,
        val expectedCost: BigDecimal,
        val expectedCostText: String,
        val costBreakdown: CostBreakdownDto,
        val enhancePath: String,
        val potentialGrade: String,
        val additionalPotentialGrade: String,
        val currentStar: Int,
        val targetStar: Int,
        val isNoljang: Boolean,
        val specialRingLevel: Int,
        val blackCubeExpectation: CubeExpectationDto,
        val additionalCubeExpectation: CubeExpectationDto,
        val starforceExpectation: StarforceExpectationDto,
        val flameExpectation: FlameExpectationDto
    )

    data class CubeExpectationDto(
        val expectedCost: BigDecimal,
        val expectedCostText: String,
        val expectedTrials: BigDecimal,
        val currentGrade: String,
        val targetGrade: String,
        val potential: String
    ) {
        companion object {
            @JvmStatic
            fun empty() = CubeExpectationDto(
                expectedCost = BigDecimal.ZERO,
                expectedCostText = "0",
                expectedTrials = BigDecimal.ZERO,
                currentGrade = "",
                targetGrade = "",
                potential = ""
            )
        }
    }

    data class StarforceExpectationDto(
        val currentStar: Int,
        val targetStar: Int,
        val isNoljang: Boolean,
        val costWithoutDestroyPrevention: BigDecimal,
        val costWithoutDestroyPreventionText: String,
        val expectedDestroyCountWithout: BigDecimal,
        val costWithDestroyPrevention: BigDecimal,
        val costWithDestroyPreventionText: String,
        val expectedDestroyCountWith: BigDecimal
    ) {
        companion object {
            @JvmStatic
            fun empty() = StarforceExpectationDto(
                currentStar = 0,
                targetStar = 0,
                isNoljang = false,
                costWithoutDestroyPrevention = BigDecimal.ZERO,
                costWithoutDestroyPreventionText = "0",
                expectedDestroyCountWithout = BigDecimal.ZERO,
                costWithDestroyPrevention = BigDecimal.ZERO,
                costWithDestroyPreventionText = "0",
                expectedDestroyCountWith = BigDecimal.ZERO
            )
        }
    }

    data class FlameExpectationDto(
        val powerfulFlameTrials: BigDecimal,
        val eternalFlameTrials: BigDecimal,
        val abyssFlameTrials: BigDecimal
    ) {
        companion object {
            @JvmStatic
            fun empty() = FlameExpectationDto(
                powerfulFlameTrials = BigDecimal.ZERO,
                eternalFlameTrials = BigDecimal.ZERO,
                abyssFlameTrials = BigDecimal.ZERO
            )
        }
    }

    data class CostBreakdownDto(
        val blackCubeCost: BigDecimal,
        val redCubeCost: BigDecimal,
        val additionalCubeCost: BigDecimal,
        val starforceCost: BigDecimal
    ) {
        companion object {
            @JvmStatic
            fun empty() = CostBreakdownDto(
                blackCubeCost = BigDecimal.ZERO,
                redCubeCost = BigDecimal.ZERO,
                additionalCubeCost = BigDecimal.ZERO,
                starforceCost = BigDecimal.ZERO
            )

            /**
             * V4 Calculator CostBreakdown에서 변환
             * @param breakdown EquipmentExpectationCalculator.CostBreakdown
             */
            @JvmStatic
            fun from(breakdown: Any): CostBreakdownDto {
                // Reflection to access CostBreakdown record methods
                val blackCubeCost = breakdown.javaClass.getDeclaredMethod("blackCubeCost").invoke(breakdown) as BigDecimal
                val redCubeCost = breakdown.javaClass.getDeclaredMethod("redCubeCost").invoke(breakdown) as BigDecimal
                val additionalCubeCost = breakdown.javaClass.getDeclaredMethod("additionalCubeCost").invoke(breakdown) as BigDecimal
                val starforceCost = breakdown.javaClass.getDeclaredMethod("starforceCost").invoke(breakdown) as BigDecimal
                return CostBreakdownDto(
                    blackCubeCost = blackCubeCost,
                    redCubeCost = redCubeCost,
                    additionalCubeCost = additionalCubeCost,
                    starforceCost = starforceCost
                )
            }
        }

        fun add(other: CostBreakdownDto): CostBreakdownDto = copy(
            blackCubeCost = blackCubeCost.add(other.blackCubeCost),
            redCubeCost = redCubeCost.add(other.redCubeCost),
            additionalCubeCost = additionalCubeCost.add(other.additionalCubeCost),
            starforceCost = starforceCost.add(other.starforceCost)
        )
    }
}
