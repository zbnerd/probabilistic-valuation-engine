package maple.expectation.core.starforce.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 놀장(스타포스 스크롤) 확률 계산기 (순수 비즈니스 로직)
 *
 * <h3>놀장 특성</h3>
 * <ul>
 *   <li>최대 15성까지만 강화 가능
 *   <li>파괴 없음 - 실패 시 현금 비용만 발생
 *   <li>실패 비용: 9,400원 (캐시)
 *   <li>보호권 12성부터 사용 불가
 * </ul>
 *
 * @see maple.expectation.service.v2.starforce.config.NoljangProbabilityTable Java 원본 (module-app)
 */
object NoljangProbabilityCalculator {
    private val MC = MathContext(10, RoundingMode.HALF_UP)

    /** 놀장 최대 스타 */
    const val MAX_NOLJANG_STAR = 15

    /** 보호권 사용 가능 최대 스타 (11성까지만 보호권 사용 가능) */
    const val PROTECTION_MAX_STAR = 11

    /** 놀장 실패 비용 (캐시, 원) */
    const val FAIL_CASH_COST_KRW = 9400

    /**
     * 캐시 → 메소 환산 비율 (1원 ≈ 16,000메소, 2024년 기준)
     * 9,400원 × 16,000 = 150,400,000 메소
     */
    const val CASH_TO_MESO_RATE = 16000L

    /** 놀장 실패 비용 (메소 환산) */
    val FAIL_COST_MESO = BigDecimal.valueOf(FAIL_CASH_COST_KRW * CASH_TO_MESO_RATE)

    /**
     * 놀장 성공 확률 테이블 (star 0~14, 스타캐치 미적용)
     *
     * <p>인덱스 n = n성에서 n+1성으로 강화할 때의 성공 확률
     */
    private val SUCCESS_RATES = doubleArrayOf(
        0.60, 0.55, 0.50, 0.40, 0.30, // 0-4성: 60%, 55%, 50%, 40%, 30%
        0.20, 0.19, 0.18, 0.17, 0.16, // 5-9성: 20%, 19%, 18%, 17%, 16%
        0.16, 0.14, 0.12, 0.10, 0.10 // 10-14성: 16%, 14%, 12%, 10%, 10%
    )

    /**
     * 비용 공식의 divisor (10성 이상)
     *
     * <p>일반 스타포스와 동일
     */
    private val COST_DIVISORS = intArrayOf(
        36, 36, 36, 36, 36, // 0-4성 (기본 공식)
        36, 36, 36, 36, 36, // 5-9성 (기본 공식)
        571, 314, 214, 157, 107 // 10-14성
    )

    /**
     * 놀장 성공 확률 조회
     * @param currentStar 현재 스타 (0~14)
     * @return 성공 확률 (0.0 ~ 1.0)
     */
    fun getSuccessRate(currentStar: Int): Double {
        if (currentStar < 0 || currentStar >= MAX_NOLJANG_STAR) {
            return 0.0
        }
        return SUCCESS_RATES[currentStar]
    }

    /**
     * 스타캐치 적용된 성공 확률 조회
     * @param currentStar 현재 스타 (0~14)
     * @param useStarCatch 스타캐치 사용 여부
     * @return 성공 확률 (스타캐치 시 1.05배, 최대 1.0)
     */
    fun getSuccessRate(currentStar: Int, useStarCatch: Boolean): Double {
        val baseRate = getSuccessRate(currentStar)
        return if (useStarCatch) {
            Math.min(baseRate * 1.05, 1.0)
        } else {
            baseRate
        }
    }

    /**
     * 보호권 사용 가능 여부
     * @param currentStar 현재 스타
     * @return 보호권 사용 가능 여부 (11성 이하만 가능)
     */
    fun canUseProtection(currentStar: Int): Boolean {
        return currentStar <= PROTECTION_MAX_STAR
    }

    /**
     * 놀장 단일 강화 비용 (메소)
     * @param currentStar 현재 스타 (0~14)
     * @param itemLevel 아이템 레벨
     * @return 1회 강화 비용 (메소)
     */
    fun getSingleEnhanceCost(currentStar: Int, itemLevel: Int): BigDecimal {
        if (currentStar < 0 || currentStar >= MAX_NOLJANG_STAR) {
            return BigDecimal.ZERO
        }

        val level = Math.max(1, itemLevel)
        val starFactor = currentStar + 1
        val levelCubed = (level.toLong() * level * level)

        val baseCost = BigDecimal.valueOf(1000)

        return if (currentStar < 10) {
            // 0~9성: 1000 + L³(S+1)/36
            val levelComponent = BigDecimal.valueOf(levelCubed * starFactor)
                .divide(BigDecimal.valueOf(36L), MC)
            roundToNearest100(baseCost.add(levelComponent))
        } else {
            // 10성+: 1000 + L³(S+1)^2.7/divisor
            val starPower = Math.pow(starFactor.toDouble(), 2.7)
            val divisor = COST_DIVISORS[currentStar]

            val levelComponent = BigDecimal.valueOf(levelCubed)
                .multiply(BigDecimal.valueOf(starPower))
                .divide(BigDecimal.valueOf(divisor.toLong()), MC)

            roundToNearest100(baseCost.add(levelComponent))
        }
    }

    /**
     * 놀장 기대 비용 계산 (0성 → 목표 스타)
     *
     * <h3>놀장 기대값 공식</h3>
     * E[비용] = Σ (강화비용 + 실패비용 × (1-p)/p) / p
     *
     * <p>여기서 p = 성공 확률, 파괴 확률 = 0
     *
     * @param targetStar 목표 스타 (1~15)
     * @param itemLevel 아이템 레벨
     * @param useStarCatch 스타캐치 사용 여부
     * @param useDiscount 30% 할인 적용 여부
     * @return 기대 비용 (메소)
     */
    fun getExpectedCost(
        targetStar: Int,
        itemLevel: Int,
        useStarCatch: Boolean,
        useDiscount: Boolean
    ): BigDecimal {
        return getExpectedCostFromStar(0, targetStar, itemLevel, useStarCatch, useDiscount)
    }

    /**
     * 놀장 기대 비용 계산 (현재 스타 → 목표 스타)
     * @param currentStar 현재 스타 (0~14)
     * @param targetStar 목표 스타 (1~15)
     * @param itemLevel 아이템 레벨
     * @param useStarCatch 스타캐치 사용 여부
     * @param useDiscount 30% 할인 적용 여부
     * @return 기대 비용 (메소)
     */
    fun getExpectedCostFromStar(
        currentStar: Int,
        targetStar: Int,
        itemLevel: Int,
        useStarCatch: Boolean,
        useDiscount: Boolean
    ): BigDecimal {
        var adjustedTarget = targetStar
        if (adjustedTarget > MAX_NOLJANG_STAR) {
            adjustedTarget = MAX_NOLJANG_STAR
        }
        if (currentStar >= adjustedTarget || currentStar < 0) {
            return BigDecimal.ZERO
        }

        var totalExpected = BigDecimal.ZERO

        for (star in currentStar until adjustedTarget) {
            val singleCost = computeExpectedCostForSingleStar(
                star, itemLevel, useStarCatch, useDiscount
            )
            totalExpected = totalExpected.add(singleCost)
        }

        return totalExpected.setScale(0, RoundingMode.HALF_UP)
    }

    /** 단일 스타 강화 기대값 계산 */
    private fun computeExpectedCostForSingleStar(
        star: Int,
        itemLevel: Int,
        useStarCatch: Boolean,
        useDiscount: Boolean
    ): BigDecimal {
        // 성공 확률
        val successRate = getSuccessRate(star, useStarCatch)
        if (successRate <= 0) {
            return BigDecimal.valueOf(Long.MAX_VALUE)
        }

        // 강화 비용 (메소)
        var enhanceCost = getSingleEnhanceCost(star, itemLevel)
        if (useDiscount) {
            enhanceCost = enhanceCost.multiply(BigDecimal.valueOf(0.7))
        }

        // 실패 확률
        val failRate = 1.0 - successRate

        // 기대 시도 횟수 = 1 / 성공확률
        val expectedTrials = BigDecimal.ONE.divide(BigDecimal.valueOf(successRate), MC)

        // 기대 실패 횟수 = (1 - 성공확률) / 성공확률
        val expectedFails = BigDecimal.valueOf(failRate)
            .divide(BigDecimal.valueOf(successRate), MC)

        // 총 기대 비용 = (강화비용 × 기대시도횟수) + (실패비용 × 기대실패횟수)
        val enhanceTotal = enhanceCost.multiply(expectedTrials)
        val failTotal = FAIL_COST_MESO.multiply(expectedFails)

        return enhanceTotal.add(failTotal)
    }

    /** 100 단위로 반올림 */
    private fun roundToNearest100(value: BigDecimal): BigDecimal {
        return value.divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
    }
}
