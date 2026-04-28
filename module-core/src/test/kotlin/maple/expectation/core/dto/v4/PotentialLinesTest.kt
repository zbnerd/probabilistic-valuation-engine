package maple.expectation.core.dto.v4

import maple.expectation.core.domain.model.PotentialGrade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PotentialLinesTest {
    @Test
    fun `grade is required`() {
        assertThat(PotentialLines(
            grade = PotentialGrade.LEGENDARY,
            line1 = "공격력 +12%",
            line2 = "보스 공격 시 데미지 +40%",
            line3 = "크리티컬 데미지 +8%"
        ).grade).isEqualTo(PotentialGrade.LEGENDARY)
    }

    @Test
    fun `lines can be null for empty options`() {
        val lines = PotentialLines(
            grade = PotentialGrade.RARE,
            line1 = null,
            line2 = null,
            line3 = null
        )
        assertThat(lines.line1).isNull()
    }

    @Test
    fun `all three lines are accessible`() {
        val lines = PotentialLines(
            grade = PotentialGrade.EPIC,
            line1 = "A",
            line2 = "B",
            line3 = "C"
        )
        assertThat(lines.asList()).containsExactly("A", "B", "C")
    }
}
