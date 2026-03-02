package maple.expectation.infrastructure.cache.tiered

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import java.util.Optional

/**
 * Abstract Tiered Cache Service Template (Issue #24)
 *
 * L1 → L2 → Warm-up 패턴의 중복을 제거하기 위한 추상 템플릿입니다.
 *
 * @param T 캐시할 데이터 타입
 * @param cacheName 캐시 이름
 * @param tieredCacheManager L1+L2 Tiered 캐시 매니저
 * @param l1CacheManager L1-only 캐시 매니저
 * @param executor LogicExecutor
 */
abstract class AbstractTieredCacheService<T>(
    protected val cacheName: String,
    tieredCacheManager: CacheManager,
    l1CacheManager: CacheManager,
    protected val executor: LogicExecutor
) {
    protected val tieredCache: Cache = requireNotNull(tieredCacheManager.getCache(cacheName)) {
        "Tiered cache '$cacheName' must not be null"
    }
    protected val l1OnlyCache: Cache = requireNotNull(l1CacheManager.getCache(cacheName)) {
        "L1-only cache '$cacheName' must not be null"
    }

    // ==================== Template Methods (Subclasses MUST Implement) ====================

    /**
     * Null Marker 여부를 판단하는 메서드 (서브클래스 구현)
     */
    protected abstract fun isValidNullMarker(value: T?): Boolean

    /**
     * 캐시 키를 로그용으로 마스킹하는 메서드 (선택 구현)
     */
    protected open fun maskKey(key: String): String = key

    /**
     * L2 저장 실패 시 로그를 기록하는 메서드 (선택 구현)
     */
    protected open fun logL2SaveFailure(key: String, value: T?, error: Throwable) {
        log.warn("[Cache] L2 SAVE FAIL | cache={} | key={} | err={}", cacheName, maskKey(key), error.toString())
    }

    /**
     * L1 warm-up 완료 로그를 기록하는 메서드 (선택 구현)
     */
    protected open fun logL1WarmupComplete(key: String) {
        log.debug("[Cache] L1 warm-up completed | cache={} | key={}", cacheName, maskKey(key))
    }

    // ==================== Core Operations (L1 → L2 → Warm-up) ====================

    /**
     * Tiered 캐시에서 유효한 값 조회 (L1 → L2 → Warm-up)
     */
    protected fun getFromTieredCache(key: String, type: Class<T>): Optional<T> {
        return executor.execute(
            {
                // 1. L1 조회 (TieredCache의 L1 계층)
                val cached = tieredCache.get(key, type)
                if (cached != null && !isValidNullMarker(cached)) {
                    logCacheHit("L1", key, cached)
                    @Suppress("UNCHECKED_CAST")
                    return@execute Optional.of(cached) as Optional<T>
                }

                // 2. L2 조회 (TieredCache의 L2 계층은 자동으로 수행됨)
                logCacheMiss(key)
                @Suppress("UNCHECKED_CAST")
                return@execute Optional.empty<T>() as Optional<T>
            },
            TaskContext.of(cacheName, "GetFromTiered", maskKey(key))
        )
    }

    /**
     * L1-only 캐시에서 유효한 값 조회 (L2 우회)
     */
    protected fun getFromL1Only(key: String, type: Class<T>): Optional<T> {
        return executor.execute(
            {
                val cached = l1OnlyCache.get(key, type)
                if (cached != null && !isValidNullMarker(cached)) {
                    logCacheHit("L1-Only", key, cached)
                    @Suppress("UNCHECKED_CAST")
                    return@execute Optional.of(cached) as Optional<T>
                }
                logCacheMiss(key)
                @Suppress("UNCHECKED_CAST")
                return@execute Optional.empty<T>() as Optional<T>
            },
            TaskContext.of(cacheName, "GetFromL1Only", maskKey(key))
        )
    }

    /**
     * Tiered 캐시에 값 저장 (L2 → L1 순서)
     */
    protected fun saveToTieredCache(key: String, value: T?, nullMarker: T) {
        executor.executeVoidJava(
            {
                val valueToStore = value ?: nullMarker
                tieredCache.put(key, valueToStore)
                logCacheSave("Tiered", key, valueToStore)
            },
            TaskContext.of(cacheName, "SaveToTiered", maskKey(key))
        )
    }

    /**
     * L1-only 캐시에 값 저장 (L2 우회, DB 저장도 스킵)
     */
    protected fun saveToL1Only(key: String, value: T?, nullMarker: T) {
        executor.executeVoidJava(
            {
                val valueToStore = value ?: nullMarker
                l1OnlyCache.put(key, valueToStore)
                logCacheSave("L1-Only", key, valueToStore)
            },
            TaskContext.of(cacheName, "SaveToL1Only", maskKey(key))
        )
    }

    /**
     * 캐시 키 생성 (형식: {cacheName}:v1:{keyParts})
     */
    protected fun buildCacheKey(vararg keyParts: String): String {
        return "$cacheName:v1:${keyParts.joinToString(":")}"
    }

    /**
     * 캐시 키 생성 (버전 지정)
     */
    protected fun buildCacheKey(version: String, vararg keyParts: String): String {
        return "$cacheName:$version:${keyParts.joinToString(":")}"
    }

    // ==================== Logging Helpers ====================

    /** 캐시 히트 로그 */
    protected open fun logCacheHit(layer: String, key: String, value: T?) {
        log.info("[Cache] {} HIT | cache={} | key={}", layer, cacheName, maskKey(key))
    }

    /** 캐시 미스 로그 */
    protected open fun logCacheMiss(key: String) {
        log.info("[Cache] MISS | cache={} | key={}", cacheName, maskKey(key))
    }

    /** 캐시 저장 로그 */
    protected open fun logCacheSave(layer: String, key: String, value: T?) {
        log.debug("[Cache] {} SAVE | cache={} | key={}", layer, cacheName, maskKey(key))
    }

    /** 캐시 무효화 로그 */
    protected open fun logCacheEvict(key: String) {
        log.debug("[Cache] EVICT | cache={} | key={}", cacheName, maskKey(key))
    }

    // ==================== Cache Invalidation ====================

    /** Tiered 캐시에서 키 무효화 */
    fun evictFromTieredCache(key: String) {
        executor.executeVoidJava(
            {
                tieredCache.evict(key)
                logCacheEvict(key)
            },
            TaskContext.of(cacheName, "EvictTiered", maskKey(key))
        )
    }

    /** L1-only 캐시에서 키 무효화 */
    fun evictFromL1OnlyCache(key: String) {
        executor.executeVoidJava(
            {
                l1OnlyCache.evict(key)
                logCacheEvict(key)
            },
            TaskContext.of(cacheName, "EvictL1Only", maskKey(key))
        )
    }

    /** Tiered 캐시 전체 무효화 */
    fun clearTieredCache() {
        executor.executeVoidJava(
            {
                tieredCache.clear()
                log.info("[Cache] CLEAR | cache={} | layer=Tiered", cacheName)
            },
            TaskContext.of(cacheName, "ClearTiered")
        )
    }

    /** L1-only 캐시 전체 무효화 */
    fun clearL1OnlyCache() {
        executor.executeVoidJava(
            {
                l1OnlyCache.clear()
                log.info("[Cache] CLEAR | cache={} | layer=L1-Only", cacheName)
            },
            TaskContext.of(cacheName, "ClearL1Only")
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(AbstractTieredCacheService::class.java)
    }
}
