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

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }

    class Builder {
        private var userIgn: String? = null
        private var calculatedAt: LocalDateTime? = null
        private var fromCache: Boolean = false
        private var totalExpectedCost: BigDecimal? = null
        private var totalCostText: String? = null
        private var totalCostBreakdown: CostBreakdownDto? = null
        private var maxPresetNo: Int = 0
        private var presets: List<PresetExpectation>? = null

        fun userIgn(v: String) = apply { userIgn = v }
        fun calculatedAt(v: LocalDateTime) = apply { calculatedAt = v }
        fun fromCache(v: Boolean) = apply { fromCache = v }
        fun totalExpectedCost(v: BigDecimal) = apply { totalExpectedCost = v }
        fun totalCostText(v: String) = apply { totalCostText = v }
        fun totalCostBreakdown(v: CostBreakdownDto) = apply { totalCostBreakdown = v }
        fun maxPresetNo(v: Int) = apply { maxPresetNo = v }
        fun presets(v: List<PresetExpectation>) = apply { presets = v }
        fun build() = EquipmentExpectationResponseV4(
            userIgn = userIgn!!,
            calculatedAt = calculatedAt!!,
            fromCache = fromCache,
            totalExpectedCost = totalExpectedCost!!,
            totalCostText = totalCostText!!,
            totalCostBreakdown = totalCostBreakdown!!,
            maxPresetNo = maxPresetNo,
            presets = presets!!
        )
    }

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
        val potentialGrade: String?,
        val additionalPotentialGrade: String?,
        val currentStar: Int,
        val targetStar: Int,
        val isNoljang: Boolean,
        val specialRingLevel: Int,
        val blackCubeExpectation: CubeExpectationDto,
        val additionalCubeExpectation: CubeExpectationDto,
        val starforceExpectation: StarforceExpectationDto,
        val flameExpectation: FlameExpectationDto
    ) {
        companion object {
            @JvmStatic
            fun builder() = ItemBuilder()
        }

        class ItemBuilder {
            private var itemName: String? = null
            private var itemIcon: String? = null
            private var itemPart: String? = null
            private var itemLevel: Int = 0
            private var expectedCost: BigDecimal? = null
            private var expectedCostText: String? = null
            private var costBreakdown: CostBreakdownDto? = null
            private var enhancePath: String? = null
            private var potentialGrade: String? = null
            private var additionalPotentialGrade: String? = null
            private var currentStar: Int = 0
            private var targetStar: Int = 0
            private var isNoljang: Boolean = false
            private var specialRingLevel: Int = 0
            private var blackCubeExpectation: CubeExpectationDto? = null
            private var additionalCubeExpectation: CubeExpectationDto? = null
            private var starforceExpectation: StarforceExpectationDto? = null
            private var flameExpectation: FlameExpectationDto? = null

            fun itemName(v: String) = apply { itemName = v }
            fun itemIcon(v: String) = apply { itemIcon = v }
            fun itemPart(v: String) = apply { itemPart = v }
            fun itemLevel(v: Int) = apply { itemLevel = v }
            fun expectedCost(v: BigDecimal) = apply { expectedCost = v }
            fun expectedCostText(v: String) = apply { expectedCostText = v }
            fun costBreakdown(v: CostBreakdownDto) = apply { costBreakdown = v }
            fun enhancePath(v: String) = apply { enhancePath = v }
            fun potentialGrade(v: String?) = apply { potentialGrade = v }
            fun additionalPotentialGrade(v: String?) = apply { additionalPotentialGrade = v }
            fun currentStar(v: Int) = apply { currentStar = v }
            fun targetStar(v: Int) = apply { targetStar = v }
            fun isNoljang(v: Boolean) = apply { isNoljang = v }
            fun specialRingLevel(v: Int) = apply { specialRingLevel = v }
            fun blackCubeExpectation(v: CubeExpectationDto) = apply { blackCubeExpectation = v }
            fun additionalCubeExpectation(v: CubeExpectationDto) = apply { additionalCubeExpectation = v }
            fun starforceExpectation(v: StarforceExpectationDto) = apply { starforceExpectation = v }
            fun flameExpectation(v: FlameExpectationDto) = apply { flameExpectation = v }
            fun build() = ItemExpectationV4(
                itemName = itemName!!,
                itemIcon = itemIcon!!,
                itemPart = itemPart!!,
                itemLevel = itemLevel,
                expectedCost = expectedCost!!,
                expectedCostText = expectedCostText!!,
                costBreakdown = costBreakdown!!,
                enhancePath = enhancePath!!,
                potentialGrade = potentialGrade,
                additionalPotentialGrade = additionalPotentialGrade,
                currentStar = currentStar,
                targetStar = targetStar,
                isNoljang = isNoljang,
                specialRingLevel = specialRingLevel,
                blackCubeExpectation = blackCubeExpectation!!,
                additionalCubeExpectation = additionalCubeExpectation!!,
                starforceExpectation = starforceExpectation!!,
                flameExpectation = flameExpectation!!
            )
        }
    }

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

            @JvmStatic
            fun builder() = CubeBuilder()
        }

        class CubeBuilder {
            private var expectedCost: BigDecimal? = null
            private var expectedCostText: String? = null
            private var expectedTrials: BigDecimal? = null
            private var currentGrade: String? = null
            private var targetGrade: String? = null
            private var potential: String? = null

            fun expectedCost(v: BigDecimal) = apply { expectedCost = v }
            fun expectedCostText(v: String) = apply { expectedCostText = v }
            fun expectedTrials(v: BigDecimal) = apply { expectedTrials = v }
            fun currentGrade(v: String) = apply { currentGrade = v }
            fun targetGrade(v: String) = apply { targetGrade = v }
            fun potential(v: String) = apply { potential = v }
            fun build() = CubeExpectationDto(
                expectedCost = expectedCost!!,
                expectedCostText = expectedCostText!!,
                expectedTrials = expectedTrials!!,
                currentGrade = currentGrade!!,
                targetGrade = targetGrade!!,
                potential = potential!!
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

            @JvmStatic
            fun builder() = StarforceBuilder()
        }

        class StarforceBuilder {
            private var currentStar: Int = 0
            private var targetStar: Int = 0
            private var isNoljang: Boolean = false
            private var costWithoutDestroyPrevention: BigDecimal? = null
            private var costWithoutDestroyPreventionText: String? = null
            private var expectedDestroyCountWithout: BigDecimal? = null
            private var costWithDestroyPrevention: BigDecimal? = null
            private var costWithDestroyPreventionText: String? = null
            private var expectedDestroyCountWith: BigDecimal? = null

            fun currentStar(v: Int) = apply { currentStar = v }
            fun targetStar(v: Int) = apply { targetStar = v }
            fun isNoljang(v: Boolean) = apply { isNoljang = v }
            fun costWithoutDestroyPrevention(v: BigDecimal) = apply { costWithoutDestroyPrevention = v }
            fun costWithoutDestroyPreventionText(v: String) = apply { costWithoutDestroyPreventionText = v }
            fun expectedDestroyCountWithout(v: BigDecimal) = apply { expectedDestroyCountWithout = v }
            fun costWithDestroyPrevention(v: BigDecimal) = apply { costWithDestroyPrevention = v }
            fun costWithDestroyPreventionText(v: String) = apply { costWithDestroyPreventionText = v }
            fun expectedDestroyCountWith(v: BigDecimal) = apply { expectedDestroyCountWith = v }
            fun build() = StarforceExpectationDto(
                currentStar = currentStar,
                targetStar = targetStar,
                isNoljang = isNoljang,
                costWithoutDestroyPrevention = costWithoutDestroyPrevention!!,
                costWithoutDestroyPreventionText = costWithoutDestroyPreventionText!!,
                expectedDestroyCountWithout = expectedDestroyCountWithout!!,
                costWithDestroyPrevention = costWithDestroyPrevention!!,
                costWithDestroyPreventionText = costWithDestroyPreventionText!!,
                expectedDestroyCountWith = expectedDestroyCountWith!!
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

            @JvmStatic
            fun builder() = FlameBuilder()
        }

        class FlameBuilder {
            private var powerfulFlameTrials: BigDecimal? = null
            private var eternalFlameTrials: BigDecimal? = null
            private var abyssFlameTrials: BigDecimal? = null

            fun powerfulFlameTrials(v: BigDecimal) = apply { powerfulFlameTrials = v }
            fun eternalFlameTrials(v: BigDecimal) = apply { eternalFlameTrials = v }
            fun abyssFlameTrials(v: BigDecimal) = apply { abyssFlameTrials = v }
            fun build() = FlameExpectationDto(
                powerfulFlameTrials = powerfulFlameTrials!!,
                eternalFlameTrials = eternalFlameTrials!!,
                abyssFlameTrials = abyssFlameTrials!!
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
