package maple.expectation.core.util

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * 🔥 P0 FIX #3: Precision Regression Tests for BigDecimal → Double Migration
 *
 * <h3>Purpose</h3>
 * Verify that Double-based calculations maintain acceptable precision compared to BigDecimal.
 * This test suite ensures numerical accuracy after performance optimization migration.
 *
 * <h3>Test Strategy</h3>
 * - Compare BigDecimal (baseline) vs Double + Kahan Summation (current implementation)
 * - Verify relative error is within acceptable threshold (0.01%)
 * - Test edge cases: large numbers, small numbers, mixed magnitudes
 *
 * @see Issue #9fd488d (BigDecimal → Double + Kahan Summation)
 */
@Tag("unit")
@DisplayName("P0-3: BigDecimal vs Double Precision Regression Tests")
class BigDecimalVsDoublePrecisionTest {

    companion object {
        // Acceptable relative error threshold (0.01%)
        private const val RELATIVE_ERROR_THRESHOLD = 0.0001

        // MathContext for BigDecimal calculations (matches typical financial precision)
        private val MATH_CONTEXT = MathContext(34, RoundingMode.HALF_UP)
    }

    @Test
    @DisplayName("Simple accumulation - double values should match BigDecimal within threshold")
    fun testSimpleAccumulation() {
        val values = listOf(1.1, 2.2, 3.3, 4.4, 5.5)

        val bigDecimalSum = calculateBigDecimalSum(values)
        val kahanSum = KahanSummation.sum(values)

        val relativeError = calculateRelativeError(bigDecimalSum, kahanSum)
        assertThat(relativeError)
            .`as`("Relative error should be below threshold (${RELATIVE_ERROR_THRESHOLD * 100}%)")
            .isLessThan(RELATIVE_ERROR_THRESHOLD)
    }

    @Test
    @DisplayName("Large numbers - double values should maintain precision")
    fun testLargeNumberAccumulation() {
        val values = listOf(1_000_000.1, 2_000_000.2, 3_000_000.3, 4_000_000.4, 5_000_000.5)

        val bigDecimalSum = calculateBigDecimalSum(values)
        val kahanSum = KahanSummation.sum(values)

        val relativeError = calculateRelativeError(bigDecimalSum, kahanSum)
        assertThat(relativeError)
            .`as`("Relative error for large numbers should be below threshold")
            .isLessThan(RELATIVE_ERROR_THRESHOLD)
    }

    @Test
    @DisplayName("Small numbers - double values should maintain precision")
    fun testSmallNumberAccumulation() {
        val values = listOf(0.0001, 0.0002, 0.0003, 0.0004, 0.0005)

        val bigDecimalSum = calculateBigDecimalSum(values)
        val kahanSum = KahanSummation.sum(values)

        val relativeError = calculateRelativeError(bigDecimalSum, kahanSum)
        assertThat(relativeError)
            .`as`("Relative error for small numbers should be below threshold")
            .isLessThan(RELATIVE_ERROR_THRESHOLD)
    }

    @Test
    @DisplayName("Mixed magnitudes - Kahan summation should prevent precision loss")
    fun testMixedMagnitudeAccumulation() {
        // Classic case where naive summation fails: adding small to large numbers
        val values = listOf(1_000_000.0, 0.0001, 2_000_000.0, 0.0001, 3_000_000.0)

        val bigDecimalSum = calculateBigDecimalSum(values)
        val kahanSum = KahanSummation.sum(values)

        // Naive double sum would lose precision here
        val naiveSum = values.sum()

        val relativeErrorKahan = calculateRelativeError(bigDecimalSum, kahanSum)
        val relativeErrorNaive = calculateRelativeError(bigDecimalSum, naiveSum)

        assertThat(relativeErrorKahan)
            .`as`("Kahan summation should maintain precision")
            .isLessThan(RELATIVE_ERROR_THRESHOLD)

        // Kahan should be as good as or better than naive summation
        // (In some cases, both may achieve 0.0 error, which is acceptable)
        assertThat(relativeErrorKahan)
            .`as`("Kahan summation should be as good as or better than naive summation")
            .isLessThanOrEqualTo(relativeErrorNaive)
    }

    @Test
    @DisplayName("Financial calculation simulation - expected cost calculation")
    fun testFinancialCalculation() {
        // Simulate expected cost calculation: sum of (probability * cost)
        val probabilities = listOf(0.15, 0.25, 0.35, 0.20, 0.05)
        val costs = listOf(1000000.0, 2000000.0, 3000000.0, 4000000.0, 5000000.0)

        val bigDecimalResult = calculateBigDecimalExpectedCost(probabilities, costs)
        val kahanResult = calculateKahanExpectedCost(probabilities, costs)

        val relativeError = calculateRelativeError(bigDecimalResult, kahanResult)
        assertThat(relativeError)
            .`as`("Expected cost calculation should maintain precision")
            .isLessThan(RELATIVE_ERROR_THRESHOLD)
    }

    @Test
    @DisplayName("Large dataset - 1000 values")
    fun testLargeDataset() {
        val values = (1..1000).map { it * 1.1 }

        val bigDecimalSum = calculateBigDecimalSum(values)
        val kahanSum = KahanSummation.sum(values)

        val relativeError = calculateRelativeError(bigDecimalSum, kahanSum)
        assertThat(relativeError)
            .`as`("Large dataset summation should maintain precision")
            .isLessThan(RELATIVE_ERROR_THRESHOLD)
    }

    @Test
    @DisplayName("Edge case - empty list")
    fun testEmptyList() {
        val values = emptyList<Double>()

        val bigDecimalSum = calculateBigDecimalSum(values)
        val kahanSum = KahanSummation.sum(values)

        assertThat(kahanSum).isEqualTo(bigDecimalSum)
    }

    @Test
    @DisplayName("Edge case - single value")
    fun testSingleValue() {
        val values = listOf(1234.5678)

        val bigDecimalSum = calculateBigDecimalSum(values)
        val kahanSum = KahanSummation.sum(values)

        val relativeError = calculateRelativeError(bigDecimalSum, kahanSum)
        assertThat(relativeError).isLessThan(RELATIVE_ERROR_THRESHOLD)
    }

    @Test
    @DisplayName("Edge case - negative numbers")
    fun testNegativeNumbers() {
        val values = listOf(-1.1, -2.2, 3.3, -4.4, 5.5)

        val bigDecimalSum = calculateBigDecimalSum(values)
        val kahanSum = KahanSummation.sum(values)

        val relativeError = calculateRelativeError(bigDecimalSum, kahanSum)
        assertThat(relativeError)
            .`as`("Negative number summation should maintain precision")
            .isLessThan(RELATIVE_ERROR_THRESHOLD)
    }

    // ==================== Helper Methods ====================

    /**
     * Calculate sum using BigDecimal (baseline for precision comparison)
     */
    private fun calculateBigDecimalSum(values: List<Double>): Double = values
        .fold(BigDecimal.ZERO) { acc, value ->
            acc.add(BigDecimal.valueOf(value), MATH_CONTEXT)
        }
        .toDouble()

    /**
     * Calculate expected cost using BigDecimal
     * E[X] = Σ(P(x) * Cost(x))
     */
    private fun calculateBigDecimalExpectedCost(
        probabilities: List<Double>,
        costs: List<Double>,
    ): Double {
        require(probabilities.size == costs.size) {
            "Probabilities and costs must have the same size"
        }

        return probabilities.indices.fold(BigDecimal.ZERO) { acc, i ->
            val product = BigDecimal.valueOf(probabilities[i])
                .multiply(BigDecimal.valueOf(costs[i]), MATH_CONTEXT)
            acc.add(product, MATH_CONTEXT)
        }.toDouble()
    }

    /**
     * Calculate expected cost using Kahan Summation
     * E[X] = Σ(P(x) * Cost(x))
     */
    private fun calculateKahanExpectedCost(
        probabilities: List<Double>,
        costs: List<Double>,
    ): Double {
        require(probabilities.size == costs.size) {
            "Probabilities and costs must have the same size"
        }

        val products = probabilities.indices.map { i ->
            probabilities[i] * costs[i]
        }

        return KahanSummation.sum(products)
    }

    /**
     * Calculate relative error between expected (BigDecimal) and actual (Double) values
     *
     * Formula: |expected - actual| / |expected|
     */
    private fun calculateRelativeError(expected: Double, actual: Double): Double {
        if (expected == 0.0) {
            // If expected is 0, use absolute error instead
            return kotlin.math.abs(actual)
        }
        return kotlin.math.abs((expected - actual) / expected)
    }
}
