package maple.expectation.infrastructure.cache

import org.springframework.cache.Cache
import org.springframework.cache.Cache.ValueWrapper
import org.springframework.cache.support.AbstractCacheManager
import org.springframework.cache.support.NoOpCache

/**
 * Caffeine-Only Cache Manager (Issue #555)
 *
 * <h3>Purpose</h3>
 *
 * <p>No-op L2 cache substitute for Caffeine-only deployments.
 * When L2 is disabled ({@code cache.l2.enabled=false}), this manager
 * provides a stub implementation that gracefully handles all operations.
 *
 * <h3>Design Pattern: Null Object Pattern</h3>
 *
 * <ul>
 *   <li>All cache operations return immediately without side effects</li>
 *   <li>TieredCache can use this as L2 drop-in replacement</li>
 *   <li>No Redis/PostgreSQL dependency required</li>
 * </ul>
 *
 * <h3>Behavior</h3>
 *
 * <ul>
 *   <li>{@link #getCache(String)}: Returns NoOpCache (no-op implementation)</li>
 *   <li>All read operations return null</li>
 *   <li>All write operations are no-ops</li>
 * </ul>
 *
 * @see NoOpCache
 * @see TieredCacheManager
 */
class CaffeineOnlyCacheManager : AbstractCacheManager() {

    /**
     * No-op Cache implementation for L2-disabled mode
     *
     * Public visibility to allow TieredCache to check for L2 disabled mode
     */
    class NoOpCacheImplementation : Cache {
        override fun getName(): String = L1_ONLY_CACHE_NAME

        override fun getNativeCache(): Any = this

        override fun get(key: Any): ValueWrapper? = null

        override fun <T : Any?> get(key: Any, type: Class<T>?): T? = null

        override fun <T : Any?> get(key: Any, valueLoader: java.util.concurrent.Callable<T>): T = valueLoader.call()

        override fun put(key: Any, value: Any?) {
            // No-op: L1 cache handles all storage
        }

        override fun putIfAbsent(key: Any, value: Any?): ValueWrapper? = null

        override fun evict(key: Any) {
            // No-op: L1 cache handles eviction
        }

        override fun clear() {
            // No-op: L1 cache handles clearing
        }

        override fun invalidate(): Boolean {
            // No-op: L1 cache handles invalidation
            return true
        }
    }

    companion object {
        private const val L1_ONLY_CACHE_NAME = "l1-only"
    }

    private val noOpCache = NoOpCacheImplementation()

    override fun loadCaches(): Collection<out Cache> = emptyList()

    /**
     * Returns a no-op cache for L2-disabled mode
     *
     * <p>TieredCache will receive this as L2 and skip all L2 operations.
     *
     * @param name Cache name (ignored, returns same no-op instance)
     * @return NoOpCache instance
     */
    override fun getCache(name: String): Cache? = noOpCache
}
