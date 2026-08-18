package maple.expectation.core.calculation.cube

import maple.expectation.core.calculation.error.MissingProbabilityException
import maple.expectation.core.calculation.error.ValuationInvariantException
import maple.expectation.core.calculation.probability.ProbabilityKey
import maple.expectation.core.calculation.probability.ProbabilityRow
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.calculation.probability.ProbabilityTableVersion
import maple.expectation.core.domain.model.CubeType
import maple.expectation.core.domain.stat.StatType
import maple.expectation.error.exception.OptionParseException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test

class SlotDistributionBuilderTest {

    private val builder = SlotDistributionBuilder()

    @Test
    fun `extracts direct all-stat compound and zero contributions into one normalized slot`() {
        val result = builder.build(
            key(),
            StatType.STR_PERCENT,
            snapshot(
                ProbabilityRow("STR +12%", 0.20),
                ProbabilityRow("올스탯 +9%", 0.15),
                ProbabilityRow("STR/DEX +6%", 0.10),
                ProbabilityRow("DEX +12%", 0.25),
                ProbabilityRow("피격 시 10% 확률로 데미지 무시", 0.30),
            ),
        )

        assertThat(result.pmf.getValues()).containsExactly(0, 6, 9, 12)
        assertThat(result.pmf.getProbs()).containsExactly(0.55, 0.10, 0.15, 0.20)
        assertThat(result.massDeviation).isZero()
    }

    @Test
    fun `rejects a missing slot instead of inventing a zero-contribution distribution`() {
        val table = ProbabilityTableSnapshot(version(), emptyMap())

        assertThatThrownBy { builder.build(key(), StatType.STR_PERCENT, table) }
            .isInstanceOf(MissingProbabilityException::class.java)
    }

    @Test
    fun `rows present but all non-target become zero contribution with full probability`() {
        val result = builder.build(
            key(),
            StatType.STR_PERCENT,
            snapshot(
                ProbabilityRow("DEX +12%", 0.4),
                ProbabilityRow("피격 시 10% 확률로 데미지 무시", 0.6),
            ),
        )

        assertThat(result.pmf.getValues()).containsExactly(0)
        assertThat(result.pmf.getProbs()).containsExactly(1.0)
    }

    @Test
    fun `normalizes mass below and above one regardless of legacy policy label`() {
        val below = builder.build(
            key(),
            StatType.STR_PERCENT,
            snapshot(
                ProbabilityRow("STR +12%", 0.49999),
                ProbabilityRow("DEX +12%", 0.49999),
            ),
        )
        val above = builder.build(
            key(),
            StatType.STR_PERCENT,
            snapshot(
                ProbabilityRow("STR +12%", 0.50001),
                ProbabilityRow("DEX +12%", 0.50001),
            ),
        )

        assertThat(below.pmf.getProbs()).containsExactly(0.5, 0.5)
        assertThat(below.massDeviation).isCloseTo(0.00002, offset(1e-15))
        assertThat(above.pmf.getProbs()).containsExactly(0.5, 0.5)
        assertThat(above.massDeviation).isCloseTo(0.00002, offset(1e-15))
    }

    @Test
    fun `primary-stat-looking parse drift remains a typed failure`() {
        assertThatThrownBy {
            builder.build(
                key(),
                StatType.STR_PERCENT,
                snapshot(ProbabilityRow("올 스탯 +9%", 1.0)),
            )
        }.isInstanceOf(OptionParseException::class.java)
    }

    @Test
    fun `snapshot rejects negative NaN and infinite rates before distribution construction`() {
        assertThatThrownBy { ProbabilityRow("STR +12%", -0.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ProbabilityRow("STR +12%", Double.NaN) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ProbabilityRow("STR +12%", Double.POSITIVE_INFINITY) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `zero total mass is a typed invariant failure`() {
        assertThatThrownBy {
            builder.build(
                key(),
                StatType.STR_PERCENT,
                snapshot(ProbabilityRow("STR +12%", 0.0)),
            )
        }.isInstanceOf(ValuationInvariantException::class.java)
    }

    private fun snapshot(vararg rows: ProbabilityRow): ProbabilityTableSnapshot = ProbabilityTableSnapshot(
        version(),
        linkedMapOf(key() to rows.toList()),
    )

    private fun key(slot: Int = 1): ProbabilityKey = ProbabilityKey(
        cubeType = CubeType.BLACK,
        level = 200,
        part = "모자",
        grade = "레전드리",
        slot = slot,
    )

    private fun version(): ProbabilityTableVersion = ProbabilityTableVersion("test-v1", SHA)

    private companion object {
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
