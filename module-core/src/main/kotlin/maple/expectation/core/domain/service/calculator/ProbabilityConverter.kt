package maple.expectation.core.domain.service.calculator

import maple.expectation.core.domain.model.calculator.DensePmf
import maple.expectation.core.domain.model.calculator.SparsePmf
import maple.expectation.error.exception.ProbabilityInvariantException

/**
 * Domain Service for Probability Conversion.
 *
 * <p>This is a PURE domain service with NO Spring dependencies, NO infrastructure concerns. It
 * encapsulates business logic for converting sparse probability distributions to dense
 * distributions using dynamic programming (convolution).
 *
 * <p><b>Core Algorithm</b>: Slot-wise Convolution with Tail Clamping
 *
 * <ul>
 *   <li>Each slot (line) independently accumulates outcomes
 *   <li>Slots are independent (no cross-slot probability)
 *   <li>Same outcome may appear in multiple slots (cumulative probability)
 *   <li>Tail Clamp: probabilities beyond target are accumulated into target bucket
 * </ul>
 *
 * <h3>Design Principles</h3>
 *
 * <ul>
 *   <li><b>Static Methods</b>: All methods are static, no state
 *   <li><b>Validation</b>: All inputs are validated before processing
 *   <li><b>Clean Architecture</b>: Zero dependencies on infrastructure layer
 * </ul>
 *
 * <h3>Business Rules</h3>
 *
 * <ul>
 *   <li>Result PMF sum must be 1.0 ± 1e-12
 *   <li>No NaN or Inf values allowed
 *   <li>No negative probabilities allowed
 *   <li>No probabilities > 1.0 allowed
 * </ul>
 *
 * @see DiceRollProbability
 * @see SparsePmf
 * @see DensePmf
 */
object ProbabilityConverter {

    private const val MASS_TOLERANCE = 1e-12
    private const val NEGATIVE_TOLERANCE = -1e-15

    /**
     * Convolve multiple slot PMFs into single dense PMF.
     *
     * <p><b>Preconditions:</b>
     *
     * <ul>
     *   <li>Mass conservation: Σ=1 ± MASS_TOLERANCE
     *   <li>No NaN/Inf allowed
     *   <li>If enableTailClamp=true, max value = target+1
     * </ul>
     *
     * @param slotPmfs list of slot PMFs
     * @param target target sum (number of successful rolls)
     * @param enableTailClamp whether to enable tail clamping
     * @return convolved dense PMF
     * @throws ProbabilityInvariantException if invariant is violated
     */
    @JvmStatic
    fun convolveAll(
        slotPmfs: List<SparsePmf>,
        target: Int,
        enableTailClamp: Boolean,
    ): DensePmf {
        if (slotPmfs == null) {
            throw IllegalArgumentException("slotPmfs cannot be null")
        }
        if (target < 0) {
            throw IllegalArgumentException("target must be non-negative")
        }

        val maxIndex = if (enableTailClamp) target else calculateMaxSum(slotPmfs)
        val acc = initializeAccumulator(maxIndex)

        var currentAcc = acc
        for (slot in slotPmfs) {
            currentAcc = convolveSlot(currentAcc, slot, maxIndex)
        }

        val result = DensePmf.fromArray(currentAcc)
        validateInvariants(result)
        return result
    }

    /**
     * Initializes probability accumulator array.
     *
     * @param maxIndex maximum index to allocate
     * @return initialized accumulator with p(0)=1.0
     */
    private fun initializeAccumulator(maxIndex: Int): DoubleArray {
        val acc = DoubleArray(maxIndex + 1)
        acc[0] = 1.0 // Initial state: probability of sum=0 is 100%
        return acc
    }

    /**
     * Convolves a single slot into the accumulator.
     *
     * @param acc current accumulator
     * @param slot slot PMF to convolve
     * @param maxIndex maximum index
     * @return updated accumulator
     */
    private fun convolveSlot(acc: DoubleArray, slot: SparsePmf, maxIndex: Int): DoubleArray {
        val next = DoubleArray(maxIndex + 1)

        for (i in 0..maxIndex) {
            if (acc[i] == 0.0) continue
            accumulateSlotContributions(acc, slot, next, i, maxIndex)
        }

        return next
    }

    /**
     * Accumulates slot contributions to next state.
     *
     * @param acc current accumulator
     * @param slot slot PMF
     * @param next next accumulator (output)
     * @param currentIndex current index being processed
     * @param maxIndex maximum index
     */
    private fun accumulateSlotContributions(
        acc: DoubleArray,
        slot: SparsePmf,
        next: DoubleArray,
        currentIndex: Int,
        maxIndex: Int,
    ) {
        for (k in 0 until slot.size()) {
            val value = slot.valueAt(k)
            val prob = slot.probAt(k)

            // P2 Fix (PR #159 Code refactoring): Guard against negative values
            // Prevents ArrayIndexOutOfBoundsException when parsing/extraction bugs occur
            if (value < 0) {
                throw maple.expectation.error.exception.ProbabilityInvariantException(
                    "Negative contribution detected: value=$value (slot index=$k)",
                )
            }

            val targetIndex = minOf(currentIndex + value, maxIndex) // Tail Clamp
            next[targetIndex] += acc[currentIndex] * prob
        }
    }

    /**
     * Calculates maximum possible sum across all slots.
     *
     * @param slotPmfs list of slot PMFs
     * @return maximum sum value
     */
    private fun calculateMaxSum(slotPmfs: List<SparsePmf>): Int = slotPmfs.sumOf { it.maxValue() }

    /**
     * Validates DensePmf invariants using Kahan summation.
     *
     * @param pmf PMF to validate
     * @throws ProbabilityInvariantException if invariant is violated
     */
    private fun validateInvariants(pmf: DensePmf) {
        val sum = pmf.totalMassKahan()
        if (Math.abs(sum - 1.0) > MASS_TOLERANCE) {
            throw maple.expectation.error.exception.ProbabilityInvariantException(
                "Mass conservation violated: Σp=$sum",
            )
        }
        if (pmf.hasNegative(NEGATIVE_TOLERANCE)) {
            throw maple.expectation.error.exception.ProbabilityInvariantException(
                "Negative probability detected",
            )
        }
        if (pmf.hasNaNOrInf()) {
            throw maple.expectation.error.exception.ProbabilityInvariantException("NaN/Inf detected")
        }
        if (pmf.hasValueExceedingOne()) {
            throw maple.expectation.error.exception.ProbabilityInvariantException(
                "Probability > 1 detected",
            )
        }
    }
}
