package maple.expectation.infrastructure.cache.per

import java.io.Serializable

/**
 * PER 캐시 래퍼 (#219)
 *
 * <h3>X-Fetch 알고리즘 지원</h3>
 *
 * <p>값과 함께 계산 소요 시간(delta), 만료 시각(expiry)을 저장하여 확률적 조기 재계산을 판단합니다.
 *
 * <h4>구조</h4>
 *
 * <pre>
 * {
 *   "value": {...},
 // 실제 캐시 데이터
 *   "delta": 150,
 // 계산 소요 시간 (ms)
 *   "expiry": 1706000000000   // 만료 시각 (Unix ms)
 * }
 * </pre>
 *
 * <h4>PER 공식</h4>
 *
 * <pre>
 * shouldRefresh = -log(random) * beta * delta >= (expiry - now)
 * </pre>
 *
 * @param T 캐시 값 타입
 * @see ProbabilisticCache
 * @see ProbabilisticCacheAspect
 */
data class CachedWrapper<T>(
    /** 캐시된 값 */
    var value: T?,

    /**
     * 계산 소요 시간 (밀리초)
     *
     * <p>원본 메서드 실행 시간을 측정하여 저장. PER 알고리즘에서 갱신 확률 계산에 사용.
     */
    var delta: Long,

    /** 만료 시각 (Unix timestamp, 밀리초) */
    var expiry: Long,
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /**
         * 팩토리 메서드
         *
         * @param value 캐시 값
         * @param deltaMs 계산 소요 시간 (ms)
         * @param ttlSeconds TTL (초)
         */
        @JvmStatic
        fun <T> of(value: T, deltaMs: Long, ttlSeconds: Long): CachedWrapper<T> {
            val expiryMs = System.currentTimeMillis() + (ttlSeconds * 1000)
            return CachedWrapper(value, deltaMs, expiryMs)
        }
    }

    /**
     * PER 알고리즘으로 갱신 여부 판단
     *
     * <h4>X-Fetch 공식</h4>
     *
     * <pre>
     * -log(random) * beta * delta >= (expiry - now)
     * </pre>
     *
     * <h4>확률 분포</h4>
     *
     * <ul>
     *   <li>만료까지 많이 남음 → 낮은 갱신 확률</li>
     *   <li>만료 임박 → 높은 갱신 확률</li>
     *   <li>delta가 클수록 → 더 일찍 갱신 시작</li>
     * </ul>
     *
     * @param beta 재계산 민감도 (1.0 = 표준)
     * @return 백그라운드 갱신이 필요하면 true
     */
    fun shouldRefresh(beta: Double): Boolean {
        val now = System.currentTimeMillis()
        val gap = expiry - now

        // 이미 만료됨 → 무조건 갱신 필요
        if (gap <= 0) {
            return true
        }

        // X-Fetch 공식: -log(random) * beta * delta >= gap
        // random ∈ (0, 1] → -log(random) ∈ [0, ∞)
        val threshold = -Math.log(Math.random()) * beta * delta
        return threshold >= gap
    }

    /** 캐시 만료 여부 (PER 미적용) */
    fun isExpired(): Boolean = System.currentTimeMillis() >= expiry

    /** 남은 TTL (밀리초) */
    fun remainingTtl(): Long = Math.max(0, expiry - System.currentTimeMillis())

    /** 캐시된 값 반환 (명시적 접근자) */
    fun getCachedValue(): T? = value
}
