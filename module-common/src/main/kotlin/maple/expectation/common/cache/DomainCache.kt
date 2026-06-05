package maple.expectation.common.cache

import java.time.Duration

/**
 * Technology-neutral cache abstraction.
 *
 * <p>Adapters in module-infra wrap Spring Cache, Caffeine, Redis, etc.
 * Business logic depends only on this interface so the backing store
 * can be swapped without domain code changes.
 */
interface DomainCache {
    fun <T : Any> get(key: String, type: Class<T>): T?
    fun put(key: String, value: Any)
    fun put(key: String, value: Any, ttl: Duration)
    fun invalidate(key: String)
    fun invalidateAll()
}
