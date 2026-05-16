package maple.expectation.application.service.expectation

import java.time.LocalDateTime
import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4
import maple.expectation.core.dto.v4.EquipmentItemConverter
import org.springframework.stereotype.Component

@Component
class PureExpectationCalculator(
    private val presetHelper: PresetCalculationHelper,
) {
    fun calculate(input: CalculationInput): EquipmentExpectationResponseV4 {
        val cubeInputs = input.items.map { EquipmentItemConverter.toCubeInput(it) }

        val preset = presetHelper.calculatePreset(
            cubeInputs,
            input.presetNo,
            input.characterClass,
        )

        return EquipmentExpectationResponseV4(
            userIgn = input.userIgn,
            calculatedAt = LocalDateTime.now(),
            fromCache = false,
            totalExpectedCost = preset.totalExpectedCost,
            totalCostText = preset.totalCostText,
            totalCostBreakdown = preset.costBreakdown,
            maxPresetNo = input.presetNo,
            presets = listOf(preset),
        )
    }
}
