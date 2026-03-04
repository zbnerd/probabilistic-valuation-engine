package maple.expectation.core.probability

import maple.expectation.core.domain.model.calculator.DensePmf

/**
 * 꼬리 확률 및 기대값 계산 컴포넌트
 *
 * <h3>핵심 기능</h3>
 *
 * <ul>
 *   <li>P(X >= target) 꼬리 확률 계산
 *   <li>기하분포 기반 기대 시도 횟수 계산
 * </ul>
 *
 * <h3>Tail Clamp 최적화</h3>
 *
 * <p>Tail Clamp가 적용된 DensePmf의 경우 mass[target]이 곧 P(X >= target)이므로 별도 합산 불필요 (성능 + 정밀도 이점)
 */
class TailProbabilityCalculator {

  /**
   * P(X >= target) 꼬리 확률 산출
   *
   * <p>Tail Clamp가 적용된 경우 mass[target]이 곧 꼬리 확률입니다.
   *
   * <p>Clamp 미적용 시 Kahan summation으로 정밀하게 합산합니다.
   *
   * @param pmf 확률질량함수
   * @param target 목표 합계
   * @param tailClampApplied Tail Clamp 적용 여부
   * @return P(X >= target)
   */
  fun calculateTailProbability(pmf: DensePmf, target: Int, tailClampApplied: Boolean): Double {
    return if (tailClampApplied) {
      pmf.massAt(target)
    } else {
      kahanSumFrom(pmf, target)
    }
  }

  /**
   * 기대 시도 횟수 (기하분포 기대값)
   *
   * <p>E[N] = 1/p (성공 확률 p인 독립 시행)
   *
   * @param probability 성공 확률
   * @return 기대값 (p <= 0이면 POSITIVE_INFINITY)
   */
  fun calculateExpectedTrials(probability: Double): Double {
    return if (probability <= 0.0) {
      Double.POSITIVE_INFINITY
    } else {
      1.0 / probability
    }
  }

  /**
   * 기대 시도 횟수의 정수 올림 (UI 표시용)
   *
   * <p>주의: 이 값은 "기대값"이 아니라 "올림한 추정치"입니다.
   *
   * @param probability 성공 확률
   * @return 올림된 기대값 (p <= 0이면 Long.MAX_VALUE)
   */
  fun calculateExpectedTrialsCeil(probability: Double): Long {
    return if (probability <= 0.0) {
      Long.MAX_VALUE
    } else {
      Math.ceil(1.0 / probability).toLong()
    }
  }

  /**
   * Kahan summation으로 target 이상 인덱스의 질량 합산
   *
   * <p>DoD 1e-12 기준 충족을 위해 오차 보정 적용
   *
   * @param pmf 확률질량함수
   * @param target 시작 인덱스
   * @return Σ mass[i] for i >= target
   */
  private fun kahanSumFrom(pmf: DensePmf, target: Int): Double {
    var sum = 0.0
    var c = 0.0 // 오차 보정

    for (i in target until pmf.size()) {
      val y = pmf.massAt(i) - c
      val t = sum + y
      c = (t - sum) - y
      sum = t
    }
    return sum
  }
}
