package maple.expectation.core.calculator

import maple.expectation.core.calculation.probability.ProbabilityRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CubeRateCalculatorProbabilityRowTest {

    private val calculator = CubeRateCalculator()

    @Test
    fun `blank and unknown options preserve the legacy neutral rate`() {
        val rows = listOf(ProbabilityRow("STR +12%", 0.25))

        assertThat(calculator.getOptionRate("", rows)).isEqualTo(1.0)
        assertThat(calculator.getOptionRate("피격 시 10% 확률로 데미지 무시", rows)).isEqualTo(1.0)
    }

    @Test
    fun `known option uses the first exact row identity`() {
        val rows = listOf(
            ProbabilityRow("STR +12%", 0.25),
            ProbabilityRow("STR +12%", 0.75),
        )

        assertThat(calculator.getOptionRate("STR +12%", rows)).isEqualTo(0.25)
    }

    @Test
    fun `known option absent from rows has zero probability`() {
        val rows = listOf(ProbabilityRow("STR +12%", 1.0))

        assertThat(calculator.getOptionRate("DEX +12%", rows)).isZero()
    }
}
