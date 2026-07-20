package maple.expectation.core.calculation

import maple.expectation.core.calculation.probability.ProbabilityKey
import maple.expectation.core.calculation.probability.ProbabilityRow
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.calculation.probability.ProbabilityTableVersion
import maple.expectation.core.domain.model.CubeType
import maple.expectation.core.policy.CostCalculationStrategy
import maple.expectation.core.starforce.domain.NoljangProbabilityCalculator
import maple.expectation.core.starforce.domain.StarforceCalculationEngine
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test

class ValuationKernelTest {

    private val kernel = ValuationKernel(
        CostCalculationStrategy { type, _, _ ->
            when (type) {
                CubeType.BLACK -> 10L
                CubeType.ADDITIONAL -> 20L
                CubeType.RED -> 1L
            }
        },
    )

    @Test
    fun `composes base black additional and starforce in exact decorator order`() {
        val input = input(
            currentStar = 10,
            targetStar = 15,
            potentialGrade = "레전드리",
            potentialOptions = listOf("STR +12%", "STR +12%", "STR +12%"),
            additionalGrade = "레전드리",
            additionalOptions = listOf("DEX +12%", "DEX +12%", "DEX +12%"),
        )
        val table = table(
            blackRows = listOf(row("STR +12%", 0.8), row("DEX +12%", 0.2)),
            additionalRows = listOf(row("DEX +12%", 0.9), row("STR +12%", 0.1)),
        )
        val expectedStarforce = StarforceCalculationEngine.computeMarkovExpectedCost(
            currentStar = 10,
            targetStar = 15,
            itemLevel = 200,
            useStarCatch = true,
            useSundayMaple = true,
            useDiscount = true,
            useDestroyPrevention = false,
        ).toDouble()

        val result = kernel.calculate(input, table)

        assertThat(result.trials.blackCubeTrials).isCloseTo(1.0 / 0.512, offset(1e-12))
        assertThat(result.trials.additionalCubeTrials).isCloseTo(1.0 / 0.729, offset(1e-12))
        assertThat(result.costs.blackCubeCost).isEqualTo(20.0)
        assertThat(result.costs.additionalCubeCost).isEqualTo(20.0)
        assertThat(result.costs.starforceCost).isEqualTo(expectedStarforce)
        assertThat(result.costs.totalCost).isEqualTo(40.0 + expectedStarforce)
        assertThat(result.enhancePath).isEqualTo(
            "golden-item > 블랙큐브(윗잠) > 에디셔널큐브(아랫잠) > 스타포스(10→15성)",
        )
        assertThat(result.tableVersion).isEqualTo(table.version)
        assertThat(result.logicVersion).isEqualTo("valuation-v1")
    }

    @Test
    fun `absent components remain null and leave only the base path`() {
        val result = kernel.calculate(input(), emptyTable())

        assertThat(result.costs).isEqualTo(ComponentCosts(null, null, null))
        assertThat(result.costs.totalCost).isNull()
        assertThat(result.trials).isEqualTo(ComponentTrials(null, null))
        assertThat(result.enhancePath).isEqualTo("golden-item")
    }

    @Test
    fun `current star equal to target does not add a starforce component`() {
        val result = kernel.calculate(
            input(currentStar = 15, targetStar = 15),
            emptyTable(),
        )

        assertThat(result.costs.starforceCost).isNull()
        assertThat(result.enhancePath).isEqualTo("golden-item")
    }

    @Test
    fun `regular maximum-star cost stays at the frozen adapter value`() {
        val result = kernel.calculate(
            input(itemLevel = 250, currentStar = 0, targetStar = 30),
            emptyTable(),
        )

        assertThat(result.costs.starforceCost).isEqualTo(3_122_948_146_481_664.0)
        assertThat(result.enhancePath).isEqualTo("golden-item > 스타포스(0→30성)")
    }

    @Test
    fun `normal-range starforce cost stays at the frozen adapter value`() {
        val result = kernel.calculate(
            input(itemLevel = 150, currentStar = 10, targetStar = 15),
            emptyTable(),
        )

        assertThat(result.costs.starforceCost).isEqualTo(200_815_384.0)
        assertThat(result.enhancePath).isEqualTo("golden-item > 스타포스(10→15성)")
    }

    @Test
    fun `Noljang target is capped but keeps the frozen regular-starforce defaults`() {
        val result = kernel.calculate(
            input(noljang = true, currentStar = 0, targetStar = 20),
            emptyTable(),
        )
        val noljangFormulaCost = NoljangProbabilityCalculator.getExpectedCostFromStar(
            currentStar = 0,
            targetStar = NoljangProbabilityCalculator.MAX_NOLJANG_STAR,
            itemLevel = 200,
            useStarCatch = true,
            useDiscount = true,
        ).toDouble()

        assertThat(result.costs.starforceCost).isEqualTo(488_041_031.0)
        assertThat(result.costs.starforceCost).isNotEqualTo(noljangFormulaCost)
        assertThat(result.enhancePath).isEqualTo("golden-item > 스타포스(0→15성)")
    }

    private fun input(
        itemLevel: Int = 200,
        currentStar: Int = 0,
        targetStar: Int = 0,
        noljang: Boolean = false,
        potentialGrade: String? = null,
        potentialOptions: List<String> = emptyList(),
        additionalGrade: String? = null,
        additionalOptions: List<String> = emptyList(),
    ): ValuationInput = ValuationInput(
        itemName = "golden-item",
        part = "모자",
        equipmentPart = "모자",
        itemLevel = itemLevel,
        currentStar = currentStar,
        targetStar = targetStar,
        noljang = noljang,
        potentialGrade = potentialGrade,
        potentialOptions = potentialOptions,
        additionalGrade = additionalGrade,
        additionalOptions = additionalOptions,
    )

    private fun table(
        blackRows: List<ProbabilityRow>,
        additionalRows: List<ProbabilityRow>,
    ): ProbabilityTableSnapshot {
        val rows = linkedMapOf<ProbabilityKey, List<ProbabilityRow>>()
        (1..3).forEach { slot ->
            rows[key(CubeType.BLACK, slot)] = blackRows
            rows[key(CubeType.ADDITIONAL, slot)] = additionalRows
        }
        return ProbabilityTableSnapshot(version(), rows)
    }

    private fun emptyTable(): ProbabilityTableSnapshot = ProbabilityTableSnapshot(version(), emptyMap())

    private fun key(type: CubeType, slot: Int): ProbabilityKey = ProbabilityKey(
        cubeType = type,
        level = 200,
        part = "모자",
        grade = "레전드리",
        slot = slot,
    )

    private fun row(name: String, rate: Double): ProbabilityRow = ProbabilityRow(name, rate)

    private fun version(): ProbabilityTableVersion = ProbabilityTableVersion("csv-v1.0", SHA)

    private companion object {
        const val SHA = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
