package maple.expectation.core.probability

import maple.expectation.core.domain.flame.FlameEquipCategory
import maple.expectation.core.domain.flame.FlameType

/**
 * 환생의 불꽃 DP 기반 기대값 계산 컴포넌트
 *
 * <h3>핵심 알고리즘</h3>
 *
 * <p>"옵션 종류 중복 없이 k개 선택"을 조합-평균 DP로 처리한다.
 *
 * <p>dp[r][t] = 앞에서 처리한 옵션들 중 r개를 선택해서 캡 점수 t가 되는 확률질량의 합
 *
 * <h3>보스 장비: k=4 고정</h3>
 *
 * <p>P = dp[4][T] / C(N,4)
 *
 * <h3>그외 장비: k=1~4 균등</h3>
 *
 * <p>P = (1/4) * sum(dp[k][T] / C(N,k)) for k=1..4
 *
 * @see FlameScoreCalculator PMF 생성
 */
class FlameDpCalculator {

  /**
   * 환생의 불꽃 기대 시도 횟수 계산
   *
   * @param category 장비 분류
   * @param flameType 불꽃 종류
   * @param level 장비 레벨
   * @param weights 직업 가중치
   * @param target 목표 환산치 (스케일 10 적용된 정수)
   * @param baseAtt 무기 기본 공격력 (무기 아닐 경우 0)
   * @param baseMag 무기 기본 마력 (무기 아닐 경우 0)
   * @param optionPmfs 옵션별 PMF 리스트 (FlameScoreCalculator.buildOptionPmfs로 생성)
   * @return 기대 시도 횟수 (1/p), 불가능하면 null
   */
  fun calculateExpectedTrials(
      category: FlameEquipCategory,
      flameType: FlameType,
      level: Int,
      weights: FlameScoreCalculator.JobWeights,
      target: Int,
      baseAtt: Int,
      baseMag: Int,
      optionPmfs: List<Map<Int, Double>>
  ): Double? {
    val n = optionPmfs.size
    if (n == 0) {
      return null
    }

    val successProb =
        if (category.bossDrop) {
          calculateProbForFixedK(optionPmfs, 4, target)
        } else {
          calculateProbForUniformK(optionPmfs, target, n)
        }

    return if (successProb <= 0) null else 1.0 / successProb
  }

  /** 보스 장비: k 고정일 때 P(score >= T) */
  private fun calculateProbForFixedK(
      optionPmfs: List<Map<Int, Double>>,
      k: Int,
      target: Int
  ): Double {
    val n = optionPmfs.size
    if (k > n) {
      return 0.0
    }

    val dp = runDp(optionPmfs, k, target)
    val comb = combination(n, k)

    return if (comb == 0L) 0.0 else dp[k][target] / comb
  }

  /** 그외 장비: k=1~4 균등일 때 P(score >= T) */
  private fun calculateProbForUniformK(
      optionPmfs: List<Map<Int, Double>>,
      target: Int,
      n: Int
  ): Double {
    val maxK = minOf(4, n)
    val dp = runDp(optionPmfs, maxK, target)

    var totalProb = 0.0
    for (k in 1..maxK) {
      val comb = combination(n, k)
      if (comb > 0) {
        totalProb += dp[k][target] / comb
      }
    }
    return totalProb / 4.0
  }

  /**
   * 핵심 DP 실행
   *
   * <p>dp[r][t] = r개 옵션 선택했을 때 캡 점수 t의 확률질량 합
   *
   * <p>캡핑: t = min(T, t + val)
   */
  private fun runDp(
      optionPmfs: List<Map<Int, Double>>,
      maxK: Int,
      target: Int
  ): Array<DoubleArray> {
    val n = optionPmfs.size

    // dp[r][t]: r options selected, capped score = t
    val dp = Array(maxK + 1) { DoubleArray(target + 1) }
    dp[0][0] = 1.0

    for (i in 0 until n) {
      val pmf = optionPmfs[i]

      // Copy for "don't pick option i" case
      val next = Array(maxK + 1) { DoubleArray(target + 1) }
      for (r in 0..maxK) {
        System.arraycopy(dp[r], 0, next[r], 0, target + 1)
      }

      // "Pick option i" case: r-1 -> r
      val rMax = minOf(i + 1, maxK)
      for (r in rMax downTo 1) {
        for (t in 0..target) {
          val base = dp[r - 1][t]
          if (base == 0.0) {
            continue
          }

          for ((key, value) in pmf.entries) {
            val `val` = key
            val prob = value
            val nt = minOf(target, t + `val`)
            next[r][nt] += base * prob
          }
        }
      }

      dp.forEachIndexed { index, doubles ->
        System.arraycopy(doubles, 0, next[index], 0, target + 1)
      }
    }

    return dp
  }

  /** 조합 C(n, k) 계산 */
  private fun combination(n: Int, k: Int): Long {
    if (k > n || k < 0) {
      return 0
    }
    if (k == 0 || k == n) {
      return 1
    }

    val effectiveK = if (k > n - k) n - k else k
    var result = 1L
    for (i in 0 until effectiveK) {
      result = result * (n - i) / (i + 1)
    }
    return result
  }
}
