package maple.expectation.domain.model.calculator

/**
 * Sparse Probability Mass Function (Sparse PMF)
 *
 * <p>Purpose: Slot-specific distribution (few non-zero entries, small K)
 *
 * <p>Immutable - guaranteed by defensive copying
 *
 * <h3>Core Assumptions</h3>
 *
 * <ul>
 *   <li>Each slot (line) draws options independently
 *   <li>Draws between slots are independent (not conditional probability)
 *   <li>Same option may appear in multiple slots
 * </ul>
 *
 * <h3>SOLID Compliance</h3>
 *
 * <ul>
 *   <li>SRP: Data representation only, validation logic separated to PmfValidator
 *   <li>DIP: Depends on validation interface, implementation independent
 * </ul>
 *
 * <h3>P0: Immutability Guarantee</h3>
 *
 * <ul>
 *   <li>Defensive copying in canonical constructor
 *   <li>Defensive copying in accessors
 * </ul>
 */
data class SparsePmf internal constructor(
    private val values: IntArray,
    private val probs: DoubleArray
) {

  companion object {
    /**
     * P0: Canonical constructor defensive copying
     */
    @JvmStatic
    fun fromArrays(values: IntArray?, probs: DoubleArray?): SparsePmf {
      return SparsePmf(
        values?.copyOf() ?: intArrayOf(),
        probs?.copyOf() ?: doubleArrayOf()
      )
    }

    /**
     * Create SparsePmf from Map (sorted by value)
     *
     * <p>Note: Constructor handles cloning
     *
     * @param dist value -> probability map
     * @return sorted SparsePmf
     */
    @JvmStatic
    fun fromMap(dist: Map<Int, Double>): SparsePmf {
      val sorted = dist.entries.sortedBy { it.key }

      val values = sorted.map { it.key }.toIntArray()
      val probs = sorted.map { it.value }.toDoubleArray()
      return SparsePmf(values, probs)
    }

    /** 빈 SparsePmf 생성 */
    @JvmStatic
    fun empty(): SparsePmf = SparsePmf(intArrayOf(), doubleArrayOf())
  }

  /** P0: Accessor defensive copying (values) */
  fun getValues(): IntArray = values.copyOf()

  /** P0: Accessor defensive copying (probs) */
  fun getProbs(): DoubleArray = probs.copyOf()

  /**
   * non-zero entry count
   */
  fun size(): Int = values.size

  /** Get value by index */
  fun valueAt(idx: Int): Int = values[idx]

  /** Get probability by index */
  fun probAt(idx: Int): Double = probs[idx]

  /** Maximum value (last element since sorted) */
  fun maxValue(): Int = if (values.isNotEmpty()) values[values.size - 1] else 0

  /**
   * Get total mass
   *
   * @param useKahan whether to use Kahan summation (true for precision)
   * @return total mass
   * @deprecated Use {@link PmfCalculator#totalMass(SparsePmf, boolean)} instead
   */
  @Deprecated(
    "Use PmfCalculator.totalMass(SparsePmf, boolean) instead",
    ReplaceWith("PmfCalculator.totalMass(this, useKahan)")
  )
  fun totalMass(useKahan: Boolean): Double = if (useKahan) totalMassKahan() else totalMassSimple()

  /** Simple cumulative sum */
  private fun totalMassSimple(): Double {
    var sum = 0.0
    for (p in probs) {
      sum += p
    }
    return sum
  }

  /**
   * Precise total mass using Kahan summation
   *
   * @deprecated Use {@link PmfCalculator#totalMassKahan(SparsePmf)} instead
   */
  @Deprecated("Use PmfCalculator.totalMassKahan(SparsePmf) instead", ReplaceWith("PmfCalculator.totalMassKahan(this)"))
  fun totalMassKahan(): Double {
    var sum = 0.0
    var c = 0.0
    for (p in probs) {
      val y = p - c
      val t = sum + y
      c = (t - sum) - y
      sum = t
    }
    return sum
  }

  /** Check if any probability value is negative */
  fun hasNegative(tolerance: Double): Boolean {
    for (p in probs) {
      if (p < -tolerance) {
        return true
      }
    }
    return false
  }

  /** Check if any probability value is NaN or Infinite */
  fun hasNaNOrInf(): Boolean {
    for (p in probs) {
      if (p.isNaN() || p.isInfinite()) {
        return true
      }
    }
    return false
  }

  /** Check if any probability value exceeds 1.0 */
  fun hasValueExceedingOne(): Boolean {
    for (p in probs) {
      if (p > 1.0 + 1e-10) {
        return true
      }
    }
    return false
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as SparsePmf

    if (!values.contentEquals(other.values)) return false
    if (!probs.contentEquals(other.probs)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = values.contentHashCode()
    result = 31 * result + probs.contentHashCode()
    return result
  }
}
