package maple.expectation.application.service.expectation

import java.util.concurrent.CompletableFuture
import maple.expectation.core.domain.model.PotentialGrade
import maple.expectation.core.dto.v4.*
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class PureExpectationCalculatorTest {

    private val presetHelper: PresetCalculationHelper = mock(PresetCalculationHelper::class.java)
    private lateinit var calculator: PureExpectationCalculator

    @BeforeEach
    fun setup() {
        calculator = PureExpectationCalculator(presetHelper)
    }

    private fun stubPreset() = PresetExpectation(
        presetNo = 1,
        totalExpectedCost = 1_000_000.0,
        totalCostText = "1,000,000",
        costBreakdown = CostBreakdownDto(
            blackCubeCost = 500_000.0,
            redCubeCost = 300_000.0,
            additionalCubeCost = 100_000.0,
            starforceCost = 100_000.0,
        ),
        items = listOf(
            ItemExpectationV4(
                itemName = "테스트 무기",
                itemIcon = "",
                itemPart = "무기",
                itemLevel = 200,
                expectedCost = 1_000_000.0,
                expectedCostText = "1,000,000",
                costBreakdown = CostBreakdownDto.empty(),
                enhancePath = "에픽→유니크→레전드리",
                potentialGrade = "레전드리",
                additionalPotentialGrade = null,
                currentStar = 0,
                targetStar = 17,
                isNoljang = true,
                specialRingLevel = 0,
                blackCubeExpectation = CubeExpectationDto.empty(),
                additionalCubeExpectation = CubeExpectationDto.empty(),
                starforceExpectation = StarforceExpectationDto.empty(),
                flameExpectation = FlameExpectationDto.empty(),
            ),
        ),
    )

    @Test
    fun `calculate returns response with correct user IGN and preset data`() {
        val input = CalculationInput(
            jobId = "test-job",
            userIgn = "테스트유저",
            characterClass = "아크메이지",
            presetNo = 1,
            items = listOf(
                EquipmentItem(
                    part = EquipmentSlot.WEAPON,
                    equipmentPart = EquipmentPart.WEAPON,
                    itemName = "테스트 무기",
                    level = 200,
                    potential = PotentialLines(PotentialGrade.LEGENDARY, "INT +12%", "마력 +9%", "올스탯 +3%"),
                    additionalPotential = null,
                    starforce = 17,
                    starforceScrollFlag = StarforceScrollFlag.USED,
                    addOption = AddOption(0, 0, 3, 0, 0, 0, 0, 5, 0, 0),
                    baseAttackPower = 10,
                    baseMagicPower = 200,
                ),
            ),
        )

        val preset = stubPreset()
        whenever(presetHelper.calculatePresetAsync(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(preset))

        val result = calculator.calculate(input)

        assertEquals("테스트유저", result.userIgn)
        assertFalse(result.fromCache)
        assertEquals(1_000_000.0, result.totalExpectedCost)
        assertEquals(1, result.maxPresetNo)
        assertEquals(1, result.presets.size)
        assertEquals(1_000_000.0, result.presets[0].totalExpectedCost)
    }

    @Test
    fun `calculate propagates exceptions from preset helper`() {
        val input = CalculationInput(
            jobId = "test-job",
            userIgn = "테스트유저",
            characterClass = "아크메이지",
            presetNo = 1,
            items = emptyList(),
        )

        whenever(presetHelper.calculatePresetAsync(any(), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("Calculation failed")))

        assertThrows(Exception::class.java) {
            calculator.calculate(input)
        }
    }
}
