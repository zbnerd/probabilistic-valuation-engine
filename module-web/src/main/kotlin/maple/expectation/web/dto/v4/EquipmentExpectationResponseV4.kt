package maple.expectation.web.dto.v4

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.LocalDateTime

/**
 * V4 장비 기대값 응답 DTO (#240)
 */
data class EquipmentExpectationResponseV4(
    val userIgn: String,
    val calculatedAt: LocalDateTime,
    val fromCache: Boolean,
    val totalExpectedCost: Double,
    val totalCostText: String,
    val totalCostBreakdown: CostBreakdownDto,
    val maxPresetNo: Int,
    val presets: List<PresetExpectation>,
) {
    /** Java 호환성을 위한 메서드 */
    @JsonIgnore
    fun isFromCache(): Boolean = fromCache

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }

    class Builder {
        private var userIgn: String? = null
        private var calculatedAt: LocalDateTime? = null
        private var fromCache: Boolean = false
        private var totalExpectedCost: Double? = null
        private var totalCostText: String? = null
        private var totalCostBreakdown: CostBreakdownDto? = null
        private var maxPresetNo: Int = 0
        private var presets: List<PresetExpectation>? = null

        fun userIgn(v: String) = apply { userIgn = v }
        fun calculatedAt(v: LocalDateTime) = apply { calculatedAt = v }
        fun fromCache(v: Boolean) = apply { fromCache = v }
        fun totalExpectedCost(v: Double) = apply { totalExpectedCost = v }
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
            presets = presets!!,
        )
    }

    data class PresetExpectation(
        val presetNo: Int,
        val totalExpectedCost: Double,
        val totalCostText: String,
        val costBreakdown: CostBreakdownDto,
        val items: List<ItemExpectationV4>,
    )

    data class ItemExpectationV4(
        val itemName: String,
        val itemIcon: String,
        val itemPart: String,
        val itemLevel: Int,
        val expectedCost: Double,
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
        val flameExpectation: FlameExpectationDto,
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
            private var expectedCost: Double? = null
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
            fun expectedCost(v: Double) = apply { expectedCost = v }
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
                flameExpectation = flameExpectation!!,
            )
        }
    }

    data class CubeExpectationDto(
        val expectedCost: Double,
        val expectedCostText: String,
        val expectedTrials: Double,
        val currentGrade: String,
        val targetGrade: String,
        val potential: String,
    ) {
        companion object {
            @JvmStatic
            fun empty() = CubeExpectationDto(
                expectedCost = 0.0,
                expectedCostText = "0",
                expectedTrials = 0.0,
                currentGrade = "",
                targetGrade = "",
                potential = "",
            )

            @JvmStatic
            fun builder() = CubeBuilder()
        }

        class CubeBuilder {
            private var expectedCost: Double? = null
            private var expectedCostText: String? = null
            private var expectedTrials: Double? = null
            private var currentGrade: String? = null
            private var targetGrade: String? = null
            private var potential: String? = null

            fun expectedCost(v: Double) = apply { expectedCost = v }
            fun expectedCostText(v: String) = apply { expectedCostText = v }
            fun expectedTrials(v: Double) = apply { expectedTrials = v }
            fun currentGrade(v: String) = apply { currentGrade = v }
            fun targetGrade(v: String) = apply { targetGrade = v }
            fun potential(v: String) = apply { potential = v }
            fun build() = CubeExpectationDto(
                expectedCost = expectedCost!!,
                expectedCostText = expectedCostText!!,
                expectedTrials = expectedTrials!!,
                currentGrade = currentGrade!!,
                targetGrade = targetGrade!!,
                potential = potential!!,
            )
        }
    }

    data class StarforceExpectationDto(
        val currentStar: Int,
        val targetStar: Int,
        val isNoljang: Boolean,
        val costWithoutDestroyPrevention: Double,
        val costWithoutDestroyPreventionText: String,
        val expectedDestroyCountWithout: Double,
        val costWithDestroyPrevention: Double,
        val costWithDestroyPreventionText: String,
        val expectedDestroyCountWith: Double,
    ) {
        companion object {
            @JvmStatic
            fun empty() = StarforceExpectationDto(
                currentStar = 0,
                targetStar = 0,
                isNoljang = false,
                costWithoutDestroyPrevention = 0.0,
                costWithoutDestroyPreventionText = "0",
                expectedDestroyCountWithout = 0.0,
                costWithDestroyPrevention = 0.0,
                costWithDestroyPreventionText = "0",
                expectedDestroyCountWith = 0.0,
            )

            @JvmStatic
            fun builder() = StarforceBuilder()
        }

        class StarforceBuilder {
            private var currentStar: Int = 0
            private var targetStar: Int = 0
            private var isNoljang: Boolean = false
            private var costWithoutDestroyPrevention: Double? = null
            private var costWithoutDestroyPreventionText: String? = null
            private var expectedDestroyCountWithout: Double? = null
            private var costWithDestroyPrevention: Double? = null
            private var costWithDestroyPreventionText: String? = null
            private var expectedDestroyCountWith: Double? = null

            fun currentStar(v: Int) = apply { currentStar = v }
            fun targetStar(v: Int) = apply { targetStar = v }
            fun isNoljang(v: Boolean) = apply { isNoljang = v }
            fun costWithoutDestroyPrevention(v: Double) = apply { costWithoutDestroyPrevention = v }
            fun costWithoutDestroyPreventionText(v: String) = apply { costWithoutDestroyPreventionText = v }
            fun expectedDestroyCountWithout(v: Double) = apply { expectedDestroyCountWithout = v }
            fun costWithDestroyPrevention(v: Double) = apply { costWithDestroyPrevention = v }
            fun costWithDestroyPreventionText(v: String) = apply { costWithDestroyPreventionText = v }
            fun expectedDestroyCountWith(v: Double) = apply { expectedDestroyCountWith = v }
            fun build() = StarforceExpectationDto(
                currentStar = currentStar,
                targetStar = targetStar,
                isNoljang = isNoljang,
                costWithoutDestroyPrevention = costWithoutDestroyPrevention!!,
                costWithoutDestroyPreventionText = costWithoutDestroyPreventionText!!,
                expectedDestroyCountWithout = expectedDestroyCountWithout!!,
                costWithDestroyPrevention = costWithDestroyPrevention!!,
                costWithDestroyPreventionText = costWithDestroyPreventionText!!,
                expectedDestroyCountWith = expectedDestroyCountWith!!,
            )
        }
    }

    data class FlameExpectationDto(
        val powerfulFlameTrials: Double,
        val eternalFlameTrials: Double,
        val abyssFlameTrials: Double,
    ) {
        companion object {
            @JvmStatic
            fun empty() = FlameExpectationDto(
                powerfulFlameTrials = 0.0,
                eternalFlameTrials = 0.0,
                abyssFlameTrials = 0.0,
            )

            @JvmStatic
            fun builder() = FlameBuilder()
        }

        class FlameBuilder {
            private var powerfulFlameTrials: Double? = null
            private var eternalFlameTrials: Double? = null
            private var abyssFlameTrials: Double? = null

            fun powerfulFlameTrials(v: Double) = apply { powerfulFlameTrials = v }
            fun eternalFlameTrials(v: Double) = apply { eternalFlameTrials = v }
            fun abyssFlameTrials(v: Double) = apply { abyssFlameTrials = v }
            fun build() = FlameExpectationDto(
                powerfulFlameTrials = powerfulFlameTrials!!,
                eternalFlameTrials = eternalFlameTrials!!,
                abyssFlameTrials = abyssFlameTrials!!,
            )
        }
    }

    data class CostBreakdownDto(
        val blackCubeCost: Double,
        val redCubeCost: Double,
        val additionalCubeCost: Double,
        val starforceCost: Double,
    ) {
        companion object {
            @JvmStatic
            fun empty() = CostBreakdownDto(
                blackCubeCost = 0.0,
                redCubeCost = 0.0,
                additionalCubeCost = 0.0,
                starforceCost = 0.0,
            )

            /**
             * V4 Calculator CostBreakdown에서 변환
             * @param breakdown EquipmentExpectationCalculator.CostBreakdown
             */
            @JvmStatic
            fun from(breakdown: Any): CostBreakdownDto {
                // Reflection to access CostBreakdown record methods
                val blackCubeCost = breakdown.javaClass.getDeclaredMethod("blackCubeCost").invoke(breakdown) as Number
                val redCubeCost = breakdown.javaClass.getDeclaredMethod("redCubeCost").invoke(breakdown) as Number
                val additionalCubeCost = breakdown.javaClass.getDeclaredMethod("additionalCubeCost").invoke(breakdown) as Number
                val starforceCost = breakdown.javaClass.getDeclaredMethod("starforceCost").invoke(breakdown) as Number
                return CostBreakdownDto(
                    blackCubeCost = blackCubeCost.toDouble(),
                    redCubeCost = redCubeCost.toDouble(),
                    additionalCubeCost = additionalCubeCost.toDouble(),
                    starforceCost = starforceCost.toDouble(),
                )
            }
        }

        fun add(other: CostBreakdownDto): CostBreakdownDto = copy(
            blackCubeCost = blackCubeCost + other.blackCubeCost,
            redCubeCost = redCubeCost + other.redCubeCost,
            additionalCubeCost = additionalCubeCost + other.additionalCubeCost,
            starforceCost = starforceCost + other.starforceCost,
        )
    }
}
