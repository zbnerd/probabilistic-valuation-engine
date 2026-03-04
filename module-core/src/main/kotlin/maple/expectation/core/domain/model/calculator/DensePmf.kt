package maple.expectation.core.domain.model.calculator

/**
 * 밀집 확률질량함수 (Dense PMF)
 *
 * <p>용도: 합성곱 결과 (인덱스 = 값)
 *
 * <p>Tail Clamp 시 size = target + 1
 *
 * <p>불변(Immutable) - 방어적 복사로 보장
 *
 * <h3>Tail Clamp 전략</h3>
 *
 * <ul>
 *   <li>인덱스는 0..target
 *   <li>합이 target 초과 시 모두 target 버킷에 누적
 *   <li>결과적으로 O(slots × target × K) 보장
 * </ul>
 *
 * <h3>P0: 불변성 보장</h3>
 *
 * <ul>
 *   <li>Canonical constructor에서 방어적 복사
 *   <li>Accessor에서 방어적 복사
 * </ul>
 */
data class DensePmf internal constructor(private val massByValue: DoubleArray) {

  companion object {
    /**
     * P0: Canonical constructor 방어적 복사
     * 외부에서 전달된 배열 수정이 내부 상태에 영향을 주지 않도록 보장
     */
    @JvmStatic
    fun fromArray(arr: DoubleArray?): DensePmf = DensePmf(arr?.copyOf() ?: doubleArrayOf())

    /** 빈 DensePmf 생성 */
    @JvmStatic
    fun empty(): DensePmf = DensePmf(doubleArrayOf())
  }

  /** P0: Accessor 방어적 복사 반환된 배열 수정이 내부 상태에 영향을 주지 않도록 보장 */
  fun getMassByValue(): DoubleArray = massByValue.copyOf()

  /** PMF 크기 (= 최대값 + 1) */
  fun size(): Int = massByValue.size

  /**
   * 특정 값의 질량 조회
   *
   * @param value 조회할 값
   * @return 해당 값의 확률 (범위 밖이면 0.0)
   */
  fun massAt(value: Int): Double {
    if (value < 0 || value >= massByValue.size) {
      return 0.0
    }
    return massByValue[value]
  }

  /** 총 질량 (단순 누적합) 빠른 근사 계산용. 검증 시에는 totalMassKahan() 사용 권장 */
  fun totalMass(): Double {
    var sum = 0.0
    for (m in massByValue) {
      sum += m
    }
    return sum
  }

  /** Kahan summation으로 정밀한 총 질량 계산 DoD 1e-12 기준 충족을 위해 검증 단계에서 사용 */
  fun totalMassKahan(): Double {
    var sum = 0.0
    var c = 0.0
    for (m in massByValue) {
      val y = m - c
      val t = sum + y
      c = (t - sum) - y
      sum = t
    }
    return sum
  }

  /**
   * 음수 확률 존재 여부
   *
   * @param tolerance 허용 오차 (예: -1e-15)
   */
  fun hasNegative(tolerance: Double): Boolean {
    for (m in massByValue) {
      if (m < tolerance) {
        return true
      }
    }
    return false
  }

  /** NaN 또는 무한대 존재 여부 */
  fun hasNaNOrInf(): Boolean {
    for (m in massByValue) {
      if (m.isNaN() || !m.isFinite()) {
        return true
      }
    }
    return false
  }

  /**
   * 1을 초과하는 확률 존재 여부 누적/보정 실수 오차 탐지용
   *
   * <p>P0: EPS 허용 오차 적용 (부동소수점 오차 감안)
   *
   * <p>1.0 + 1e-12 이하는 정상으로 간주
   */
  fun hasValueExceedingOne(): Boolean {
    val EPS = 1e-12
    for (m in massByValue) {
      if (m > 1.0 + EPS) {
        return true
      }
    }
    return false
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as DensePmf

    return massByValue.contentEquals(other.massByValue)
  }

  override fun hashCode(): Int = massByValue.contentHashCode()
}
