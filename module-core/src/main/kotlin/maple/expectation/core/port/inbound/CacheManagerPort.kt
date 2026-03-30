package maple.expectation.core.port.inbound

/**
 * Cache Manager Port (ADR-005, Issue #640)
 *
 * <p>Decouples application layer from infrastructure cache implementation.
 * Provides cache access without exposing TieredCacheManager.
 *
 * <p><b>Note:</b> Uses `Any?` to avoid Spring dependencies in module-core.
 * Implementation in module-infra casts to Spring Cache types.
 *
 * <h3>Methods</h3>
 * <ul>
 *   <li>getCache(name) - Get cache by name (returns Any for Spring Cache)</li>
 *   <li>getMeterRegistry() - Get metrics registry (returns Any for Micrometer MeterRegistry)</li>
 *   <li>getL1CacheDirect(name) - Get L1 cache directly (fast path)</li>
 * </ul>
 *
 * <h3>Implementation</h3>
 * <ul>
 *   <li>CacheManagerPortAdapter in module-infra delegates to TieredCacheManager</li>
 * </ul>
 */
interface CacheManagerPort {

    /**
     * Get cache by name.
     *
     * @param name Cache name
     * @return Cache instance (as Any to avoid Spring dependency) or null
     */
    fun getCache(name: String): Any?

    /**
     * Get metrics registry.
     *
     * @return MeterRegistry (as Any to avoid Micrometer dependency)
     */
    fun getMeterRegistry(): Any

    /**
     * Get L1 cache directly (fast path bypass).
     *
     * @param name Cache name
     * @return L1 Cache instance (as Any) or null
     */
    fun getL1CacheDirect(name: String): Any?
}
