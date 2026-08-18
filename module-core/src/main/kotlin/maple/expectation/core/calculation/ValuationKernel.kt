package maple.expectation.core.calculation

import maple.expectation.core.calculation.cube.CubeTrialInput
import maple.expectation.core.calculation.cube.CubeTrialsKernel
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.domain.model.CubeType
import maple.expectation.core.policy.CostCalculationStrategy
import maple.expectation.core.starforce.domain.StarforceCalculationEngine

class ValuationKernel(
    private val costStrategy: CostCalculationStrategy,
    private val cubeTrialsKernel: CubeTrialsKernel = CubeTrialsKernel(),
) {
    fun calculate(
        input: ValuationInput,
        table: ProbabilityTableSnapshot,
    ): ValuationResult {
        var enhancePath = input.itemName

        val black = calculateCube(
            input = input,
            table = table,
            cubeType = CubeType.BLACK,
            grade = input.potentialGrade,
            options = input.potentialOptions,
        )
        if (black != null) {
            enhancePath += BLACK_CUBE_PATH_SUFFIX
        }

        val additional = calculateCube(
            input = input,
            table = table,
            cubeType = CubeType.ADDITIONAL,
            grade = input.additionalGrade,
            options = input.additionalOptions,
        )
        if (additional != null) {
            enhancePath += ADDITIONAL_CUBE_PATH_SUFFIX
        }

        val starforce = calculateStarforce(input)
        if (starforce != null) {
            enhancePath += " > 스타포스(${input.currentStar}→${input.normalizedTargetStar}성)"
        }

        return ValuationResult(
            costs = ComponentCosts(
                blackCubeCost = black?.cost,
                additionalCubeCost = additional?.cost,
                starforceCost = starforce,
            ),
            trials = ComponentTrials(
                blackCubeTrials = black?.trials,
                additionalCubeTrials = additional?.trials,
            ),
            enhancePath = enhancePath,
            tableVersion = table.version,
            logicVersion = LOGIC_VERSION,
        )
    }

    private fun calculateCube(
        input: ValuationInput,
        table: ProbabilityTableSnapshot,
        cubeType: CubeType,
        grade: String?,
        options: List<String>,
    ): CubeComponent? {
        if (grade.isNullOrEmpty()) {
            return null
        }

        val costPerTrial = costStrategy.calculateCost(cubeType, input.itemLevel, grade)
        val result = cubeTrialsKernel.calculate(
            CubeTrialInput(
                cubeType = cubeType,
                level = input.itemLevel,
                part = input.part,
                grade = grade,
                orderedOptions = options.toList(),
            ),
            table,
        )
        val cost = Math.round(result.expectedTrials).toDouble() * costPerTrial.toDouble()
        return CubeComponent(cost = cost, trials = result.expectedTrials)
    }

    private fun calculateStarforce(input: ValuationInput): Double? {
        val requestedTarget = input.normalizedTargetStar
        if (input.currentStar >= requestedTarget) {
            return null
        }

        val maxStar = StarforceCalculationEngine.getMaxStarForLevel(input.itemLevel)
        val calculationTarget = minOf(requestedTarget, maxStar)
        StarforceCalculationEngine.validateStarRange(input.currentStar, calculationTarget, maxStar)
        if (input.currentStar >= calculationTarget) {
            return 0.0
        }

        return StarforceCalculationEngine.computeMarkovExpectedCost(
            currentStar = input.currentStar,
            targetStar = calculationTarget,
            itemLevel = input.itemLevel,
            useStarCatch = true,
            useSundayMaple = true,
            useDiscount = true,
            useDestroyPrevention = false,
        ).toDouble()
    }

    private data class CubeComponent(
        val cost: Double,
        val trials: Double,
    )

    companion object {
        const val LOGIC_VERSION = "valuation-v1"
        private const val BLACK_CUBE_PATH_SUFFIX = " > 블랙큐브(윗잠)"
        private const val ADDITIONAL_CUBE_PATH_SUFFIX = " > 에디셔널큐브(아랫잠)"
    }
}
