package maple.expectation.core.calculation.cube

import maple.expectation.core.calculation.error.MissingProbabilityException
import maple.expectation.core.calculation.probability.ProbabilityKey
import maple.expectation.core.calculation.probability.ProbabilityRow
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.calculation.probability.ProbabilityTableVersion
import maple.expectation.core.domain.model.CubeType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test

class PermutationCubeTrialsKernelTest {

    private val kernel = PermutationCubeTrialsKernel()

    @Test
    fun `sums unique permutations using the matching row set for each slot`() {
        val options = listOf("STR +12%", "DEX +12%", "INT +12%")
        val table = table(
            listOf(row(options[0], 0.1), row(options[1], 0.2), row(options[2], 0.3)),
            listOf(row(options[0], 0.4), row(options[1], 0.5), row(options[2], 0.6)),
            listOf(row(options[0], 0.7), row(options[1], 0.8), row(options[2], 0.9)),
        )

        val expectedTrials = kernel.calculate(input(options), table)

        assertThat(expectedTrials).isCloseTo(1.0 / 0.45, offset(1e-12))
    }

    @Test
    fun `duplicate target options contribute only their unique permutations`() {
        val options = listOf("STR +12%", "STR +12%", "DEX +12%")
        val rows = listOf(row("STR +12%", 0.2), row("DEX +12%", 0.3))

        val expectedTrials = kernel.calculate(input(options), table(rows, rows, rows))

        assertThat(expectedTrials).isCloseTo(1.0 / 0.036, offset(1e-12))
    }

    @Test
    fun `zero total probability returns positive infinity`() {
        val options = listOf("STR +12%", "STR +12%", "STR +12%")
        val targetRows = listOf(row("STR +12%", 1.0))
        val nonTargetRows = listOf(row("DEX +12%", 1.0))

        val expectedTrials = kernel.calculate(input(options), table(targetRows, nonTargetRows, targetRows))

        assertThat(expectedTrials).isEqualTo(Double.POSITIVE_INFINITY)
    }

    @Test
    fun `missing slot rows remain a typed invariant failure`() {
        val options = listOf("STR +12%", "STR +12%", "STR +12%")
        val table = ProbabilityTableSnapshot(
            version(),
            linkedMapOf(key(1) to listOf(row("STR +12%", 1.0))),
        )

        assertThatThrownBy { kernel.calculate(input(options), table) }
            .isInstanceOf(MissingProbabilityException::class.java)
    }

    private fun input(options: List<String>): CubeTrialInput = CubeTrialInput(
        cubeType = CubeType.BLACK,
        level = 200,
        part = "모자",
        grade = "레전드리",
        orderedOptions = options,
    )

    private fun table(
        slotOne: List<ProbabilityRow>,
        slotTwo: List<ProbabilityRow>,
        slotThree: List<ProbabilityRow>,
    ): ProbabilityTableSnapshot = ProbabilityTableSnapshot(
        version(),
        linkedMapOf(key(1) to slotOne, key(2) to slotTwo, key(3) to slotThree),
    )

    private fun key(slot: Int): ProbabilityKey = ProbabilityKey(
        CubeType.BLACK,
        200,
        "모자",
        "레전드리",
        slot,
    )

    private fun row(option: String, rate: Double): ProbabilityRow = ProbabilityRow(option, rate)

    private fun version(): ProbabilityTableVersion = ProbabilityTableVersion("test-v1", SHA)

    private companion object {
        const val SHA = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
