package maple.expectation.infrastructure.cache

import maple.expectation.core.port.inbound.CacheManagerPort

/**
 * CacheManagerPort Adapter (ADR-005, Issue #640)
 *
 * <p>Implements CacheManagerPort by delegating to TieredCacheManager.
 * Allows application layer to use cache without depending on infra implementation.
 */
class CacheManagerPortAdapter(
    private val tieredCacheManager: TieredCacheManager,
) : CacheManagerPort {

    override fun getCache(name: String): Any? = tieredCacheManager.getCache(name)

    override fun getMeterRegistry(): Any = tieredCacheManager.meterRegistry

    override fun getL1CacheDirect(name: String): Any? = tieredCacheManager.getL1CacheDirect(name)
}
