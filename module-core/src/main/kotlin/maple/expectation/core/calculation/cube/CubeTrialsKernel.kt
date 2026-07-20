package maple.expectation.core.calculation.cube

import maple.expectation.core.calculation.probability.ProbabilityKey
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.domain.model.CubeType
import maple.expectation.core.domain.stat.StatType
import maple.expectation.core.probability.ProbabilityConvolver
import maple.expectation.core.probability.TailProbabilityCalculator
import maple.expectation.error.exception.UnsupportedCalculationEngineException

data class CubeTrialInput(
    val cubeType: CubeType,
    val level: Int,
    val part: String,
    val grade: String,
    val orderedOptions: List<String>,
    val explicitTargetStat: StatType? = null,
    val explicitMinimumTotal: Int? = null,
    val dpEnabled: Boolean = false,
    val enableTailClamp: Boolean = true,
)

enum class CubeTrialMode {
    EXPLICIT_DP,
    INFERRED_DP,
    PERMUTATION,
}

data class CubeTrialResult(
    val expectedTrials: Double,
    val mode: CubeTrialMode,
)

class CubeTrialsKernel(
    private val distributionBuilder: SlotDistributionBuilder = SlotDistributionBuilder(),
    private val convolver: ProbabilityConvolver = ProbabilityConvolver(),
    private val tailCalculator: TailProbabilityCalculator = TailProbabilityCalculator(),
    private val inferrer: DpModeInferrer = DpModeInferrer(),
    private val permutationKernel: PermutationCubeTrialsKernel = PermutationCubeTrialsKernel(),
) {
    fun calculate(input: CubeTrialInput, table: ProbabilityTableSnapshot): CubeTrialResult {
        validateCommonInput(input)
        val explicit = input.explicitTargetStat != null || input.explicitMinimumTotal != null
        if (explicit) {
            require(input.explicitTargetStat != null && input.explicitMinimumTotal != null) {
                "Explicit target stat and minimum total must be supplied together"
            }
            if (!input.dpEnabled) {
                throw UnsupportedCalculationEngineException("Explicit DP requires enablement")
            }
            require(input.explicitTargetStat != StatType.UNKNOWN) { "Explicit target stat must be known" }
            require(input.explicitMinimumTotal > 0) { "Explicit minimum total must be positive" }
            return CubeTrialResult(
                expectedTrials = calculateDp(
                    input,
                    table,
                    input.explicitTargetStat,
                    input.explicitMinimumTotal,
                ),
                mode = CubeTrialMode.EXPLICIT_DP,
            )
        }

        val inference = inferrer.infer(input.orderedOptions)
        if (inference.isValid && inference.confidence >= MINIMUM_INFERENCE_CONFIDENCE) {
            val targetStat = requireNotNull(inference.targetStatType)
            return CubeTrialResult(
                expectedTrials = calculateDp(input, table, targetStat, inference.minTotal),
                mode = CubeTrialMode.INFERRED_DP,
            )
        }

        return CubeTrialResult(
            expectedTrials = permutationKernel.calculate(input, table),
            mode = CubeTrialMode.PERMUTATION,
        )
    }

    private fun calculateDp(
        input: CubeTrialInput,
        table: ProbabilityTableSnapshot,
        targetStat: StatType,
        minimumTotal: Int,
    ): Double {
        val slotDistributions = (1..SLOT_COUNT).map { slot ->
            distributionBuilder.build(
                ProbabilityKey(
                    cubeType = input.cubeType,
                    level = input.level,
                    part = input.part,
                    grade = input.grade,
                    slot = slot,
                ),
                targetStat,
                table,
            ).pmf
        }
        val total = convolver.convolveAll(slotDistributions, minimumTotal, input.enableTailClamp)
        val tailProbability = tailCalculator.calculateTailProbability(
            total,
            minimumTotal,
            input.enableTailClamp,
        )
        return tailCalculator.calculateExpectedTrials(tailProbability)
    }

    private fun validateCommonInput(input: CubeTrialInput) {
        require(input.orderedOptions.size == SLOT_COUNT) { "Exactly three ordered options are required" }
        require(input.level >= 0) { "Level must not be negative" }
        require(input.part.isNotBlank()) { "Part must not be blank" }
        require(input.grade.isNotBlank()) { "Grade must not be blank" }
    }

    private companion object {
        const val SLOT_COUNT = 3
        const val MINIMUM_INFERENCE_CONFIDENCE = 0.5
    }
}
