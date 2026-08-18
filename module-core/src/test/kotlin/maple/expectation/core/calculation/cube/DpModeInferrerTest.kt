package maple.expectation.core.calculation.cube

import maple.expectation.core.domain.stat.StatType
import maple.expectation.error.exception.OptionParseException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test

class DpModeInferrerTest {

    private val inferrer = DpModeInferrer()

    @Test
    fun `sums direct contributions and expands all-stat across four individual stats`() {
        val result = inferrer.infer(listOf("STR +12%", "올스탯 +9%"))

        assertThat(result.targetStatType).isEqualTo(StatType.STR_PERCENT)
        assertThat(result.minTotal).isEqualTo(21)
        assertThat(result.confidence).isCloseTo(21.0 / 48.0, offset(1e-12))
        assertThat(result.compound).isFalse()
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `equal contributions preserve StatType enum order`() {
        val result = inferrer.infer(listOf("STR +12%", "DEX +12%"))

        assertThat(result.targetStatType).isEqualTo(StatType.STR_PERCENT)
        assertThat(result.minTotal).isEqualTo(12)
        assertThat(result.confidence).isEqualTo(0.5)
    }

    @Test
    fun `mixed valid categories produce an immutable rejected inference`() {
        val options = mutableListOf("STR +12%", "보스 몬스터 공격 시 데미지 +40%")
        val before = options.toList()

        val result = inferrer.infer(options)

        assertThat(result).isEqualTo(DpInference(null, 0, 0.0, compound = true))
        assertThat(result.isValid).isFalse()
        assertThat(options).containsExactlyElementsOf(before)
    }

    @Test
    fun `blank and unknown options yield an empty inference`() {
        val result = inferrer.infer(
            listOf("", "피격 시 10% 확률로 데미지 무시", "오토스틸 +5%"),
        )

        assertThat(result).isEqualTo(DpInference(null, 0, 0.0, compound = false))
        assertThat(result.isValid).isFalse()
    }

    @Test
    fun `primary-stat-looking drift is not converted to empty inference`() {
        assertThatThrownBy { inferrer.infer(listOf("올 스탯 +9%")) }
            .isInstanceOf(OptionParseException::class.java)
    }
}
