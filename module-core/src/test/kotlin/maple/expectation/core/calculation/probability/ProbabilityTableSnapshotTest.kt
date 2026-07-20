package maple.expectation.core.calculation.probability

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import maple.expectation.core.calculation.error.MissingProbabilityException
import maple.expectation.core.calculation.error.ProbabilityTableInitializationException
import maple.expectation.core.domain.model.CubeType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProbabilityTableSnapshotTest {

    @Test
    fun `value types validate version key and row segments`() {
        assertThat(ProbabilityTableVersion("csv-v1.0", SHA_A))
            .isEqualTo(ProbabilityTableVersion("csv-v1.0", SHA_A))

        assertThatThrownBy { ProbabilityTableVersion("", SHA_A) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ProbabilityTableVersion("csv-v1.0", "ABC") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { key(level = -1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { key(part = "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { key(grade = " ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { key(slot = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { key(slot = 4) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ProbabilityRow("", 0.5) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ProbabilityRow("STR +12%", -0.1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ProbabilityRow("STR +12%", Double.NaN) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ProbabilityRow("STR +12%", Double.POSITIVE_INFINITY) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `preserves key and row order including exact duplicate multiplicity`() {
        val firstKey = key(slot = 1)
        val secondKey = key(slot = 2)
        val duplicate = ProbabilityRow("STR +12%", 0.25)
        val snapshot = ProbabilityTableSnapshot(
            version = version(),
            index = linkedMapOf(
                firstKey to listOf(duplicate, ProbabilityRow("DEX +12%", 0.5), duplicate),
                secondKey to listOf(ProbabilityRow("올스탯 +9%", 1.0)),
            ),
        )

        assertThat(snapshot.keys()).containsExactly(firstKey, secondKey)
        assertThat(snapshot.rows(firstKey)).containsExactly(
            duplicate,
            ProbabilityRow("DEX +12%", 0.5),
            duplicate,
        )
        assertThat(snapshot.rowCount).isEqualTo(4)
    }

    @Test
    fun `rejects conflicting rates for the same key and option identity`() {
        val tableKey = key()

        assertThatThrownBy {
            ProbabilityTableSnapshot(
                version(),
                linkedMapOf(
                    tableKey to listOf(
                        ProbabilityRow("STR +12%", 0.25),
                        ProbabilityRow("STR +12%", 0.5),
                    ),
                ),
            )
        }.isInstanceOf(ProbabilityTableInitializationException::class.java)
    }

    @Test
    fun `missing and explicitly empty supported keys are invariant failures`() {
        val emptyKey = key(slot = 1)
        val absentKey = key(slot = 2)
        val snapshot = ProbabilityTableSnapshot(version(), linkedMapOf(emptyKey to emptyList()))

        assertThatThrownBy { snapshot.rows(emptyKey) }
            .isInstanceOf(MissingProbabilityException::class.java)
        assertThatThrownBy { snapshot.rows(absentKey) }
            .isInstanceOf(MissingProbabilityException::class.java)
    }

    @Property
    fun `caller collection mutation cannot change a constructed snapshot`(
        @ForAll @IntRange(min = 1, max = 8) duplicateCount: Int,
    ) {
        val tableKey = key()
        val originalRow = ProbabilityRow("STR +12%", 0.25)
        val callerRows = MutableList(duplicateCount) { originalRow }
        val callerIndex = linkedMapOf<ProbabilityKey, List<ProbabilityRow>>(tableKey to callerRows)
        val snapshot = ProbabilityTableSnapshot(version(), callerIndex)

        callerRows.clear()
        callerIndex.clear()
        callerIndex[key(slot = 2)] = listOf(ProbabilityRow("DEX +12%", 1.0))

        assertThat(snapshot.keys()).containsExactly(tableKey)
        assertThat(snapshot.rows(tableKey)).hasSize(duplicateCount).containsOnly(originalRow)
    }

    private fun version(): ProbabilityTableVersion = ProbabilityTableVersion("csv-v1.0", SHA_A)

    private fun key(
        level: Int = 200,
        part: String = "모자",
        grade: String = "레전드리",
        slot: Int = 1,
    ): ProbabilityKey = ProbabilityKey(CubeType.BLACK, level, part, grade, slot)

    private companion object {
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
