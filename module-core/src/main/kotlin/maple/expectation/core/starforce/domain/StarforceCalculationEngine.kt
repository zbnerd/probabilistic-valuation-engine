package maple.expectation.core.starforce.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 스타포스 계산 엔진 (순수 비즈니스 로직)
 *
 * <p>이 클래스는 인프라 의존성 없이 순수 계산 로직만 포함합니다.
 * 캐싱, 예외 처리, 초기화 등은 Adapter 레벨에서 담당합니다.
 *
 * <h3>마르코프 체인 기대값 계산</h3>
 *
 * <pre>
 * E[s] = (C[s] + p*E[s+1] + d*E[12]) / (p+d)
 *
 * 순환참조 해결: E[s] = a[s]*E[12] + b[s]
 * E[12] = b[12] / (1 - a[12])
 * </pre>
 *
 * @see maple.expectation.service.v2.starforce.StarforceLookupTableImpl Java 구현체 (module-app)
 */
object StarforceCalculationEngine {

    /**
     * 레벨별 최대 스타포스 조회
     * @param itemLevel 아이템 레벨
     * @return 최대 스타포스 수
     */
    fun getMaxStarForLevel(itemLevel: Int): Int {
        return StarforceConstants.LEVEL_STAR_LIMITS
            .firstOrNull { itemLevel <= it.first }
            ?.second ?: StarforceConstants.MAX_STAR
    }

    /**
     * 단일 강화 비용 (반올림 전)
     * @param star 현재 스타
     * @param itemLevel 아이템 레벨
     * @return 기본 비용
     */
    fun getSingleEnhanceCostRaw(star: Int, itemLevel: Int): Double {
        val level = itemLevel.toLong()
        val levelCubed = level * level * level
        val starFactor = star + 1

        return if (star <= 9) {
            // 0~9성: 1000 + L³(S+1)/36
            1000.0 + (levelCubed * starFactor).toDouble() / 36.0
        } else {
            // 10성+: 1000 + L³(S+1)^2.7/divisor
            val starPower = Math.pow(starFactor.toDouble(), 2.7)
            val divisor = StarforceConstants.COST_DIVISORS[star]
            1000.0 + levelCubed * starPower / divisor
        }
    }

    /**
     * 단계별 파라미터 계산 (확률, 비용)
     * @return [성공확률, 유지확률, 파괴확률, 비용]
     */
    fun getStageParams(
        star: Int,
        itemLevel: Int,
        useStarCatch: Boolean,
        useSundayMaple: Boolean,
        useDiscount: Boolean,
        useDestroyPrevention: Boolean
    ): DoubleArray {
        var p = StarforceConstants.BASE_SUCCESS_RATES[star]
        var d = StarforceConstants.BASE_DESTROY_RATES[star]

        // 썬데이메이플: 15-21성에서 파괴율 30% 감소 (곱적용)
        if (useSundayMaple && star in 15..21) {
            d *= 0.7
        }

        var m = 1.0 - p - d

        // 파괴방지 (15-17성): 파괴율 0, 비용 3배
        var costMult = 1.0
        if (useDestroyPrevention && star in 15..17) {
            d = 0.0
            m = 1.0 - p
            costMult = 3.0 // 기본비용 + 200% = 3배
        }

        // 스타캐치: 성공률 1.05배, 나머지 비율 유지
        if (useStarCatch) {
            val adjusted = applyStarCatch(p, m, d)
            p = adjusted[0]
            m = adjusted[1]
            d = adjusted[2]
        }

        // 비용 계산
        val baseCost = getSingleEnhanceCostRaw(star, itemLevel)
        var cost = baseCost * costMult

        // 30% 할인
        if (useDiscount) {
            cost *= 0.7
        }

        // 10단위 반올림
        cost = roundToNearest10(cost)

        return doubleArrayOf(p, m, d, cost)
    }

    /** 스타캐치 적용 (성공률 1.05배, 나머지 비율 유지) */
    private fun applyStarCatch(p: Double, m: Double, d: Double): DoubleArray {
        val p2 = Math.min(1.0, p * 1.05)
        val rest = 1.0 - p2

        if (m + d < 1e-12) {
            return doubleArrayOf(p2, rest, 0.0)
        }

        // 유지:파괴 비율 유지
        val m2 = rest * (m / (m + d))
        val d2 = rest * (d / (m + d))

        return doubleArrayOf(p2, m2, d2)
    }

    /**
     * 마르코프 체인 기대값 계산 (순환참조 해결)
     *
     * <p>E[s] = a[s]*E[12] + b[s] 형태로 표현 후, E[12] = b[12] / (1 - a[12])로 닫아서 해결
     *
     * @return 기대 비용 (메소)
     */
    fun computeMarkovExpectedCost(
        currentStar: Int,
        targetStar: Int,
        itemLevel: Int,
        useStarCatch: Boolean,
        useSundayMaple: Boolean,
        useDiscount: Boolean,
        useDestroyPrevention: Boolean
    ): BigDecimal {
        val T = targetStar

        // a[s], b[s] 배열: E[s] = a[s]*E[12] + b[s]
        val a = DoubleArray(T + 1)
        val b = DoubleArray(T + 1)
        // E[T] = 0 → a[T] = 0, b[T] = 0 (이미 초기화됨)

        // T-1부터 0까지 역순으로 계산
        for (s in (T - 1) downTo 0) {
            val params = getStageParams(
                s, itemLevel, useStarCatch, useSundayMaple, useDiscount, useDestroyPrevention
            )
            val p = params[0] // 성공확률
            val d = params[2] // 파괴확률
            val c = params[3] // 비용

            val aNext = if (s + 1 >= T) 0.0 else a[s + 1]
            val bNext = if (s + 1 >= T) 0.0 else b[s + 1]

            val denom = p + d
            if (denom < 1e-12) {
                // 불가능한 경우 (성공+파괴 = 0)
                a[s] = 0.0
                b[s] = Double.MAX_VALUE
            } else {
                // a[s] = (p*a[s+1] + d*1) / (p+d)
                // b[s] = (c + p*b[s+1]) / (p+d)
                a[s] = (p * aNext + d) / denom
                b[s] = (c + p * bNext) / denom
            }
        }

        // E[12] 해결
        val E12 = if (T <= StarforceConstants.DESTROY_RESET_STAR) {
            0.0
        } else {
            // E[12] = a[12]*E[12] + b[12]
            // E[12] * (1 - a[12]) = b[12]
            // E[12] = b[12] / (1 - a[12])
            val a12 = a[StarforceConstants.DESTROY_RESET_STAR]
            val b12 = b[StarforceConstants.DESTROY_RESET_STAR]
            if (Math.abs(1 - a12) < 1e-12) {
                Double.MAX_VALUE
            } else {
                b12 / (1 - a12)
            }
        }

        // E[currentStar] = a[currentStar]*E[12] + b[currentStar]
        val result = a[currentStar] * E12 + b[currentStar]

        return BigDecimal.valueOf(result).setScale(0, RoundingMode.HALF_UP)
    }

    /**
     * 기대 파괴 횟수 계산 (마르코프 체인)
     *
     * <p>B[s] = (p*B[s+1] + d*(1 + B[12])) / (p+d)
     * B[s] = a[s]*B[12] + b[s]
     * B[12] = b[12] / (1 - a[12])
     *
     * @return 기대 파괴 횟수
     */
    fun computeExpectedDestroyCount(
        currentStar: Int,
        targetStar: Int,
        useStarCatch: Boolean,
        useSundayMaple: Boolean,
        useDestroyPrevention: Boolean
    ): BigDecimal {
        val T = targetStar

        val a = DoubleArray(T + 1)
        val b = DoubleArray(T + 1)

        for (s in (T - 1) downTo 0) {
            val params = getStageParams(
                s, 200, useStarCatch, useSundayMaple, false, useDestroyPrevention
            )
            val p = params[0]
            val d = params[2]

            val aNext = if (s + 1 >= T) 0.0 else a[s + 1]
            val bNext = if (s + 1 >= T) 0.0 else b[s + 1]

            val denom = p + d
            if (denom < 1e-12) {
                a[s] = 0.0
                b[s] = 0.0
            } else {
                // B[s] = (p*B[s+1] + d*(1 + B[12])) / (p+d)
                // = (p*B[s+1] + d + d*B[12]) / (p+d)
                // a[s] = (p*a[s+1] + d) / (p+d)
                // b[s] = (p*b[s+1] + d) / (p+d)
                a[s] = (p * aNext + d) / denom
                b[s] = (p * bNext + d) / denom
            }
        }

        // B[12] 해결
        val B12 = if (T <= StarforceConstants.DESTROY_RESET_STAR) {
            0.0
        } else {
            val a12 = a[StarforceConstants.DESTROY_RESET_STAR]
            val b12 = b[StarforceConstants.DESTROY_RESET_STAR]
            if (Math.abs(1 - a12) < 1e-12) {
                Double.MAX_VALUE
            } else {
                b12 / (1 - a12)
            }
        }

        val result = a[currentStar] * B12 + b[currentStar]
        return BigDecimal.valueOf(result).setScale(2, RoundingMode.HALF_UP)
    }

    /** 10 단위로 반올림 (메이플스토리 스타포스 비용 표시 기준) */
    fun roundToNearest10(value: Double): Double {
        return Math.floor((value + 5) / 10.0) * 10
    }

    /** 스타 범위 검증 */
    fun validateStarRange(currentStar: Int, targetStar: Int, maxStar: Int) {
        require(currentStar in 0..maxStar) { "Invalid current star: $currentStar" }
        require(targetStar in 0..maxStar) { "Invalid target star: $targetStar" }
    }

    /** 캐시 키 생성 (Adapter 레벨에서 사용) */
    fun buildCacheKey(
        currentStar: Int,
        targetStar: Int,
        level: Int,
        starCatch: Boolean,
        sunday: Boolean,
        discount: Boolean,
        destroyPrev: Boolean
    ): String {
        return "%d-%d-%d-%b-%b-%b-%b".format(
            currentStar, targetStar, level, starCatch, sunday, discount, destroyPrev
        )
    }
}
