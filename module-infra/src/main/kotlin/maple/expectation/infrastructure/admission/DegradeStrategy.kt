package maple.expectation.infrastructure.admission

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 🔥 ADVANCED: Fast-Fail + Degrade Strategy
 *
 * <h3>Purpose</h3>
 * When admission control rejects requests, return cached/stale data instead of failing.
 *
 * <h3>Degrade Levels (Priority Order)</h3>
 * <ol>
 *   <li>Fresh cache: Return L1 cache data (if available)</li>
 *   <li>Stale cache: Return stale L2 cache data with warning</li>
 *   <li>Fallback: Return default/empty response</li>
 * </ol>
 *
 * @param meterRegistry Micrometer registry
 */
@Component
class DegradeStrategy(
    private val meterRegistry: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(DegradeStrategy::class.java)

    // Metrics
    private val freshCacheCounter: Counter
    private val staleCacheCounter: Counter
    private val fallbackCounter: Counter
    private val degradeTotalCounter: Counter

    init {
        freshCacheCounter = Counter.builder("degrade.fresh_cache")
            .description("Requests served from fresh cache during degradation")
            .register(meterRegistry)

        staleCacheCounter = Counter.builder("degrade.stale_cache")
            .description("Requests served from stale cache during degradation")
            .register(meterRegistry)

        fallbackCounter = Counter.builder("degrade.fallback")
            .description("Requests served with fallback response during degradation")
            .register(meterRegistry)

        degradeTotalCounter = Counter.builder("degrade.total")
            .description("Total degraded requests")
            .register(meterRegistry)

        log.info("[DegradeStrategy] Initialized with 3-level degradation strategy")
    }

    /**
     * 🔥 DEGRADE: Handle admission rejection with cache fallback
     *
     * @param key Request key (for cache lookup)
     * @param cacheService Cache service for fallback
     * @return DegradeResponse with cached data or fallback
     */
    fun <T> handleRejection(
        key: String,
        cacheService: CacheService<T>? = null
    ): DegradeResponse<T> {
        degradeTotalCounter.increment()

        // Level 1: Try fresh cache (L1)
        if (cacheService != null) {
            val fresh = cacheService.getFresh(key)
            if (fresh != null) {
                freshCacheCounter.increment()
                log.info("[DegradeStrategy] ✅ Fresh cache hit for degraded request: key={}", key)
                return DegradeResponse.Fresh(fresh)
            }

            // Level 2: Try stale cache (L2)
            val stale = cacheService.getStale(key)
            if (stale != null) {
                staleCacheCounter.increment()
                log.warn("[DegradeStrategy] ⚠️ Stale cache fallback for: key={}", key)
                return DegradeResponse.Stale(stale)
            }
        }

        // Level 3: Fallback response
        fallbackCounter.increment()
        log.error("[DegradeStrategy] 🔴 Fallback response for: key={}", key)
        return DegradeResponse.Fallback()
    }

    /**
     * 🔥 SIMPLIFIED: Quick degradation without cache service
     */
    fun <T> quickFallback(): DegradeResponse<T> {
        degradeTotalCounter.increment()
        fallbackCounter.increment()
        return DegradeResponse.Fallback()
    }

    /**
     * Cache service interface for degradation
     */
    interface CacheService<T> {
        fun getFresh(key: String): T?
        fun getStale(key: String): T?
    }

    /**
     * Degradation response wrapper
     */
    sealed class DegradeResponse<T> {
        data class Fresh<T>(val data: T) : DegradeResponse<T>()
        data class Stale<T>(val data: T) : DegradeResponse<T>()
        class Fallback<T> : DegradeResponse<T>()
    }

}

/**
 * 🔥 USAGE EXAMPLE in Controller/Service:
 *
 * ```kotlin
 * fun getExpectation(userIgn: String): CompletableFuture<EquipmentExpectationResponseV4> {
 *     return admissionControl
 *         .submitOrWait(userIgn) { calculateExpectation(userIgn) }
 *         .exceptionally { ex ->
 *             when (ex) {
 *                 is AdmissionRejectedException -> {
 *                     val degraded = degradeStrategy.handleRejection(userIgn, cacheService)
 *                     when (degraded) {
 *                         is DegradeResponse.Fresh -> degraded.data
 *                         is DegradeResponse.Stale -> {
 *                             log.warn("Returning stale data for: $userIgn")
 *                             degraded.data
 *                         }
 *                         is DegradeResponse.Fallback -> {
 *                             throw DegradedException(
 *                                 "Service overloaded - no cached data available",
 *                                 DegradeStrategy.DegradedException.DegradationLevel.FALLBACK
 *                             )
 *                         }
 *                     }
 *                 }
 *                 else -> throw ex
 *             }
 *         }
 * }
 * ```
 */
