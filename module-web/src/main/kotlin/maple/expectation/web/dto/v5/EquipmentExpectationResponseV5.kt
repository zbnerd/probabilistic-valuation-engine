package maple.expectation.web.dto.v5

import java.math.BigDecimal
import java.time.Instant

/**
 * V5 CQRS 장비 기대값 응답 DTO
 */
data class EquipmentExpectationResponseV5(
    val userIgn: String,
    val calculatedAt: Instant,
    val fromCache: Boolean,
    val totalExpectedCost: BigDecimal,
    val totalCostText: String,
    val totalCostBreakdown: CostBreakdownDto,
    val maxPresetNo: Int,
    val presets: List<PresetExpectation>,
) {
    data class PresetExpectation(
        val presetNo: Int,
        val totalExpectedCost: BigDecimal,
        val totalCostText: String,
        val costBreakdown: CostBreakdownDto,
        val items: List<ItemExpectationV5>,
    )

    data class ItemExpectationV5(
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
        val flameExpectation: FlameExpectationDto,
    )

    data class CubeExpectationDto(
        val expectedCost: BigDecimal,
        val expectedCostText: String,
        val expectedTrials: BigDecimal,
        val currentGrade: String,
        val targetGrade: String,
        val potential: String,
    ) {
        companion object {
            @JvmStatic
            fun empty() = CubeExpectationDto(
                expectedCost = BigDecimal.ZERO,
                expectedCostText = "0",
                expectedTrials = BigDecimal.ZERO,
                currentGrade = "",
                targetGrade = "",
                potential = "",
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
        val expectedDestroyCountWith: BigDecimal,
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
                expectedDestroyCountWith = BigDecimal.ZERO,
            )
        }
    }

    data class FlameExpectationDto(
        val powerfulFlameTrials: BigDecimal,
        val eternalFlameTrials: BigDecimal,
        val abyssFlameTrials: BigDecimal,
    ) {
        companion object {
            @JvmStatic
            fun empty() = FlameExpectationDto(
                powerfulFlameTrials = BigDecimal.ZERO,
                eternalFlameTrials = BigDecimal.ZERO,
                abyssFlameTrials = BigDecimal.ZERO,
            )
        }
    }

    data class CostBreakdownDto(
        val blackCubeCost: BigDecimal,
        val redCubeCost: BigDecimal,
        val additionalCubeCost: BigDecimal,
        val starforceCost: BigDecimal,
        val flameCost: BigDecimal,
    ) {
        companion object {
            @JvmStatic
            fun empty() = CostBreakdownDto(
                blackCubeCost = BigDecimal.ZERO,
                redCubeCost = BigDecimal.ZERO,
                additionalCubeCost = BigDecimal.ZERO,
                starforceCost = BigDecimal.ZERO,
                flameCost = BigDecimal.ZERO,
            )
        }
    }
}
