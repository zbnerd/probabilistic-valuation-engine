package maple.expectation.core.calculation.cube

import maple.expectation.core.calculation.probability.ProbabilityKey
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.calculator.CubeRateCalculator

class PermutationCubeTrialsKernel(
    private val rateCalculator: CubeRateCalculator = CubeRateCalculator(),
) {
    fun calculate(input: CubeTrialInput, table: ProbabilityTableSnapshot): Double {
        require(input.orderedOptions.size == SLOT_COUNT) { "Exactly three ordered options are required" }

        val probability = uniquePermutations(input.orderedOptions).sumOf { options ->
            options.indices.fold(1.0) { caseProbability, index ->
                val rows = table.rows(
                    ProbabilityKey(
                        cubeType = input.cubeType,
                        level = input.level,
                        part = input.part,
                        grade = input.grade,
                        slot = index + 1,
                    ),
                )
                caseProbability * rateCalculator.getOptionRate(options[index], rows)
            }
        }

        return if (probability > 0.0) 1.0 / probability else Double.POSITIVE_INFINITY
    }

    private fun uniquePermutations(options: List<String>): Set<List<String>> = linkedSetOf(
        listOf(options[0], options[1], options[2]),
        listOf(options[0], options[2], options[1]),
        listOf(options[1], options[0], options[2]),
        listOf(options[1], options[2], options[0]),
        listOf(options[2], options[0], options[1]),
        listOf(options[2], options[1], options[0]),
    )

    private companion object {
        const val SLOT_COUNT = 3
    }
}
