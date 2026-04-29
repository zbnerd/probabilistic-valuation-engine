package maple.expectation.core.dto.cube

import maple.expectation.core.domain.stat.StatType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CubeComputeKeyTest {
    @Test
    fun `identical inputs produce equal keys`() {
        val input = CubeCalculationInput(
            level = 160,
            part = "무기",
            grade = "유니크",
            targetStatType = StatType.STR_PERCENT,
            minTotal = 21,
            enableTailClamp = true,
        )
        val key1 = CubeComputeKey.from(input, "BLACK", "csv-v1.0")
        val key2 = CubeComputeKey.from(input, "BLACK", "csv-v1.0")
        assertThat(key1).isEqualTo(key2)
        assertThat(key1.hashCode()).isEqualTo(key2.hashCode())
    }

    @Test
    fun `different type produces different key`() {
        val input = CubeCalculationInput(
            level = 160,
            part = "무기",
            grade = "유니크",
            targetStatType = StatType.STR_PERCENT,
            minTotal = 21,
            enableTailClamp = true,
        )
        val key1 = CubeComputeKey.from(input, "BLACK", "csv-v1.0")
        val key2 = CubeComputeKey.from(input, "ADDITIONAL", "csv-v1.0")
        assertThat(key1).isNotEqualTo(key2)
    }

    @Test
    fun `different level produces different key`() {
        val input1 = CubeCalculationInput(level = 160, part = "무기", grade = "유니크")
        val input2 = CubeCalculationInput(level = 200, part = "무기", grade = "유니크")
        val key1 = CubeComputeKey.from(input1, "BLACK", "csv-v1.0")
        val key2 = CubeComputeKey.from(input2, "BLACK", "csv-v1.0")
        assertThat(key1).isNotEqualTo(key2)
    }

    @Test
    fun `null optional fields handled correctly`() {
        val input = CubeCalculationInput(level = 160, part = "무기", grade = "유니크")
        val key1 = CubeComputeKey.from(input, "BLACK", "csv-v1.0")
        val key2 = CubeComputeKey.from(input, "BLACK", "csv-v1.0")
        assertThat(key1).isEqualTo(key2)
    }
}
