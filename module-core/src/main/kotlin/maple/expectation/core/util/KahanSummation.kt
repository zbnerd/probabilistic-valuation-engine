package maple.expectation.core.util

/**
 * Kahan Summation for accurate floating-point summation
 *
 * <h3>Purpose</h3>
 *
 * <p>Reduces floating-point error when summing many numbers by keeping track of
 * the low-order bits that are lost in regular addition.</p>
 *
 * <h3>Algorithm</h3>
 *
 * <pre>
 * y = value - c
 * t = sum + y
 * c = (t - sum) - y
 * sum = t
 * </pre>
 *
 * <h3>Performance Impact</h3>
 *
 * <p>Much faster than BigDecimal for calculations, while maintaining better
 * precision than regular double summation for large accumulations.</p>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Kahan_summation">Kahan summation</a>
 */
class KahanSummation {
    private var sum = 0.0
    private var c = 0.0  // Compensation for lost low-order bits

    /**
     * Add a value to the summation with Kahan compensation
     *
     * @param value value to add
     */
    fun add(value: Double) {
        val y = value - c
        val t = sum + y
        c = (t - sum) - y
        sum = t
    }

    /**
     * Get the current sum
     *
     * @return accumulated sum
     */
    fun sum(): Double = sum

    /**
     * Reset the accumulator
     */
    fun reset() {
        sum = 0.0
        c = 0.0
    }

    companion object {
        /**
         * Sum an array of doubles with Kahan compensation
         *
         * @param values values to sum
         * @return accurate sum
         */
        @JvmStatic
        fun sum(values: DoubleArray): Double {
            val acc = KahanSummation()
            for (v in values) {
                acc.add(v)
            }
            return acc.sum()
        }

        /**
         * Sum an iterable of doubles with Kahan compensation
         *
         * @param values values to sum
         * @return accurate sum
         */
        @JvmStatic
        fun sum(values: Iterable<Double>): Double {
            val acc = KahanSummation()
            for (v in values) {
                acc.add(v)
            }
            return acc.sum()
        }
    }
}
