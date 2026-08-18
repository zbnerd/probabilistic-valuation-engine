package maple.expectation.core.calculation.cube

import maple.expectation.core.calculation.error.MissingProbabilityException
import maple.expectation.core.calculation.probability.ProbabilityKey
import maple.expectation.core.calculation.probability.ProbabilityRow
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.calculation.probability.ProbabilityTableVersion
import maple.expectation.core.domain.model.CubeType
import maple.expectation.core.domain.stat.StatType
import maple.expectation.error.exception.UnsupportedCalculationEngineException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test

class CubeTrialsKernelTest {

    private val kernel = CubeTrialsKernel()

    @Test
    fun `explicit DP requires both fields and feature enablement`() {
        val table = threeSlotTable()

        assertThatThrownBy {
            kernel.calculate(baseInput(explicitTargetStat = StatType.STR_PERCENT), table)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            kernel.calculate(
                baseInput(
                    explicitTargetStat = StatType.STR_PERCENT,
                    explicitMinimumTotal = 12,
                    dpEnabled = false,
                ),
                table,
            )
        }.isInstanceOf(UnsupportedCalculationEngineException::class.java)
    }

    @Test
    fun `explicit DP composes exactly three slot distributions with tail clamp`() {
        val result = kernel.calculate(
            baseInput(
                explicitTargetStat = StatType.STR_PERCENT,
                explicitMinimumTotal = 24,
                dpEnabled = true,
            ),
            threeSlotTable(),
        )

        assertThat(result.mode).isEqualTo(CubeTrialMode.EXPLICIT_DP)
        assertThat(result.expectedTrials).isCloseTo(2.0, offset(1e-12))
    }

    @Test
    fun `tail probability remains identical when clamp is disabled`() {
        val input = baseInput(
            explicitTargetStat = StatType.STR_PERCENT,
            explicitMinimumTotal = 24,
            dpEnabled = true,
        ).copy(enableTailClamp = false)

        val result = kernel.calculate(input, threeSlotTable())

        assertThat(result.expectedTrials).isCloseTo(2.0, offset(1e-12))
    }

    @Test
    fun `black red and additional cubes all compose three slots`() {
        CubeType.entries.forEach { cubeType ->
            val input = baseInput(
                cubeType = cubeType,
                explicitTargetStat = StatType.STR_PERCENT,
                explicitMinimumTotal = 36,
                dpEnabled = true,
            )

            val result = kernel.calculate(input, threeSlotTable(cubeType = cubeType))

            assertThat(result.expectedTrials).isCloseTo(8.0, offset(1e-12))
        }
    }

    @Test
    fun `valid non-compound inference uses DP even when feature flag is false`() {
        val result = kernel.calculate(baseInput(), threeSlotTable())

        assertThat(result.mode).isEqualTo(CubeTrialMode.INFERRED_DP)
        assertThat(result.expectedTrials).isCloseTo(8.0, offset(1e-12))
    }

    @Test
    fun `compound categories use permutation fallback with slot-specific rows`() {
        val options = listOf(
            "STR +12%",
            "보스 몬스터 공격 시 데미지 +40%",
            "피격 시 10% 확률로 데미지 무시",
        )
        val rows = listOf(
            ProbabilityRow(options[0], 0.2),
            ProbabilityRow(options[1], 0.3),
            ProbabilityRow(options[2], 0.5),
        )
        val result = kernel.calculate(
            baseInput(orderedOptions = options),
            threeSlotTable(rows),
        )

        assertThat(result.mode).isEqualTo(CubeTrialMode.PERMUTATION)
        assertThat(result.expectedTrials).isCloseTo(1.0 / 0.36, offset(1e-12))
    }

    @Test
    fun `uninferable repeated option uses one unique permutation`() {
        val option = "피격 시 10% 확률로 데미지 무시"
        val result = kernel.calculate(
            baseInput(orderedOptions = listOf(option, option, option)),
            threeSlotTable(listOf(ProbabilityRow(option, 1.0))),
        )

        assertThat(result).isEqualTo(CubeTrialResult(1.0, CubeTrialMode.PERMUTATION))
    }

    @Test
    fun `any missing one of the three DP slots is an invariant failure`() {
        val table = ProbabilityTableSnapshot(
            version(),
            linkedMapOf(
                key(1) to defaultRows(),
                key(2) to defaultRows(),
            ),
        )

        assertThatThrownBy { kernel.calculate(baseInput(), table) }
            .isInstanceOf(MissingProbabilityException::class.java)
    }

    private fun baseInput(
        cubeType: CubeType = CubeType.BLACK,
        orderedOptions: List<String> = listOf("STR +12%", "STR +12%", "STR +12%"),
        explicitTargetStat: StatType? = null,
        explicitMinimumTotal: Int? = null,
        dpEnabled: Boolean = false,
    ): CubeTrialInput = CubeTrialInput(
        cubeType = cubeType,
        level = 200,
        part = "모자",
        grade = "레전드리",
        orderedOptions = orderedOptions,
        explicitTargetStat = explicitTargetStat,
        explicitMinimumTotal = explicitMinimumTotal,
        dpEnabled = dpEnabled,
    )

    private fun threeSlotTable(
        rows: List<ProbabilityRow> = defaultRows(),
        cubeType: CubeType = CubeType.BLACK,
    ): ProbabilityTableSnapshot =
        ProbabilityTableSnapshot(
            version(),
            linkedMapOf(
                key(1, cubeType) to rows,
                key(2, cubeType) to rows,
                key(3, cubeType) to rows,
            ),
        )

    private fun defaultRows(): List<ProbabilityRow> = listOf(
        ProbabilityRow("STR +12%", 0.5),
        ProbabilityRow("DEX +12%", 0.5),
    )

    private fun key(slot: Int, cubeType: CubeType = CubeType.BLACK): ProbabilityKey = ProbabilityKey(
        cubeType = cubeType,
        level = 200,
        part = "모자",
        grade = "레전드리",
        slot = slot,
    )

    private fun version(): ProbabilityTableVersion = ProbabilityTableVersion("test-v1", SHA)

    private companion object {
        const val SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
