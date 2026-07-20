package maple.expectation.core.calculation

import maple.expectation.core.calculation.probability.ProbabilityKey
import maple.expectation.core.calculation.probability.ProbabilityRow
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.calculation.probability.ProbabilityTableVersion
import maple.expectation.core.domain.model.CubeType
import maple.expectation.core.policy.CostCalculationStrategy
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.assertj.core.api.Assertions.assertThat

class ValuationKernelPropertyTest {

    private val kernel = ValuationKernel(CostCalculationStrategy { _, _, _ -> 17L })

    @Property(tries = 30)
    fun `calculation is deterministically repeatable for the same canonical input and snapshot`(
        @ForAll @IntRange(min = 10, max = 90) targetRatePercent: Int,
    ) {
        val input = input(listOf("STR +12%", "STR +12%", "STR +12%"))
        val targetRate = targetRatePercent / 100.0
        val rows = listOf(
            ProbabilityRow("STR +12%", targetRate),
            ProbabilityRow("DEX +12%", 1.0 - targetRate),
        )
        val table = ProbabilityTableSnapshot(
            version(),
            (1..3).associate { slot -> key(slot) to rows },
        )

        assertThat(kernel.calculate(input, table)).isEqualTo(kernel.calculate(input, table))
    }

    @Property(tries = 30)
    fun `ordered options participate in canonical input identity`(
        @ForAll @IntRange(min = 1, max = 99) value: Int,
    ) {
        val ordered = listOf("STR +$value%", "DEX +$value%", "INT +$value%")
        val original = input(ordered)
        val reordered = input(ordered.reversed())

        assertThat(original).isNotEqualTo(reordered)
        assertThat(original.potentialOptions).containsExactlyElementsOf(ordered)
        assertThat(reordered.potentialOptions).containsExactlyElementsOf(ordered.reversed())
    }

    private fun input(options: List<String>): ValuationInput = ValuationInput(
        itemName = "property-item",
        part = "모자",
        equipmentPart = "모자",
        itemLevel = 200,
        currentStar = 0,
        targetStar = 0,
        noljang = false,
        potentialGrade = "레전드리",
        potentialOptions = options,
        additionalGrade = null,
        additionalOptions = emptyList(),
    )

    private fun key(slot: Int): ProbabilityKey = ProbabilityKey(
        CubeType.BLACK,
        200,
        "모자",
        "레전드리",
        slot,
    )

    private fun version(): ProbabilityTableVersion = ProbabilityTableVersion("csv-v1.0", SHA)

    private companion object {
        const val SHA = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    }
}
