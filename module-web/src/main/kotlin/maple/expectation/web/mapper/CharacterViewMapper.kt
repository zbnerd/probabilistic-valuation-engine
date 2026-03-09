package maple.expectation.web.mapper

import java.math.BigDecimal
import java.util.Optional
import maple.expectation.infrastructure.mongodb.CharacterValuationView
import maple.expectation.web.dto.v5.EquipmentExpectationResponseV5
import maple.expectation.web.dto.v5.EquipmentExpectationResponseV5.*

/**
 * V5 CQRS: MongoDB View → V5 Response DTO Mapper
 */
object CharacterViewMapper {

    @JvmStatic
    fun toResponseDto(view: CharacterValuationView?): Optional<EquipmentExpectationResponseV5> {
        if (view == null) return Optional.empty()

        val totalCost = view.totalExpectedCost?.toLong() ?: 0L

        return Optional.of(
            EquipmentExpectationResponseV5(
                userIgn = view.userIgn ?: "",
                calculatedAt = view.calculatedAt ?: java.time.Instant.now(),
                fromCache = view.fromCache ?: true,
                totalExpectedCost = view.totalExpectedCost?.toBigDecimal() ?: BigDecimal.ZERO,
                totalCostText = formatCostText(totalCost),
                totalCostBreakdown = CostBreakdownDto.empty(),
                maxPresetNo = view.maxPresetNo ?: 1,
                presets = toPresetDtos(view.presets),
            ),
        )
    }

    private fun toPresetDtos(presets: List<CharacterValuationView.PresetView>?): List<PresetExpectation> = presets?.mapNotNull { toPresetDto(it) } ?: emptyList()

    private fun toPresetDto(preset: CharacterValuationView.PresetView?): PresetExpectation? {
        if (preset == null) return null

        return PresetExpectation(
            presetNo = preset.presetNo ?: 0,
            totalExpectedCost = preset.totalExpectedCost?.toBigDecimal() ?: BigDecimal.ZERO,
            totalCostText = preset.totalCostText ?: "",
            costBreakdown = toCostBreakdownDto(preset.costBreakdown),
            items = toItemDtos(preset.items),
        )
    }

    private fun toItemDtos(items: List<CharacterValuationView.ItemExpectationView>?): List<ItemExpectationV5> = items?.mapNotNull { toItemDto(it) } ?: emptyList()

    private fun toItemDto(item: CharacterValuationView.ItemExpectationView?): ItemExpectationV5? {
        if (item == null) return null

        return ItemExpectationV5(
            itemName = item.itemName ?: "",
            itemIcon = "",
            itemPart = "",
            itemLevel = 0,
            expectedCost = item.expectedCost?.toBigDecimal() ?: BigDecimal.ZERO,
            expectedCostText = item.costText ?: "",
            costBreakdown = CostBreakdownDto.empty(),
            enhancePath = "",
            potentialGrade = "",
            additionalPotentialGrade = "",
            currentStar = 0,
            targetStar = 0,
            isNoljang = false,
            specialRingLevel = 0,
            blackCubeExpectation = CubeExpectationDto.empty(),
            additionalCubeExpectation = CubeExpectationDto.empty(),
            starforceExpectation = StarforceExpectationDto.empty(),
            flameExpectation = FlameExpectationDto.empty(),
        )
    }

    private fun toCostBreakdownDto(breakdown: CharacterValuationView.CostBreakdownView?): CostBreakdownDto {
        if (breakdown == null) return CostBreakdownDto.empty()

        return CostBreakdownDto(
            blackCubeCost = breakdown.blackCubeCost?.toBigDecimal() ?: BigDecimal.ZERO,
            redCubeCost = breakdown.redCubeCost?.toBigDecimal() ?: BigDecimal.ZERO,
            additionalCubeCost = breakdown.additionalCubeCost?.toBigDecimal() ?: BigDecimal.ZERO,
            starforceCost = breakdown.starforceCost?.toBigDecimal() ?: BigDecimal.ZERO,
            flameCost = breakdown.flameCost?.toBigDecimal() ?: BigDecimal.ZERO,
        )
    }

    private fun formatCostText(cost: Long?): String {
        if (cost == null) return "0"

        val jo = cost / 1_0000_0000_0000L
        val uk = (cost % 1_0000_0000_0000L) / 1_0000_0000L
        val man = (cost % 1_0000_0000L) / 1_0000L

        val sb = StringBuilder()
        if (jo > 0) sb.append(jo).append("조 ")
        if (uk > 0) sb.append(uk).append("억 ")
        if (man > 0 || sb.isEmpty()) sb.append(man).append("만")

        return sb.toString().trim()
    }
}
