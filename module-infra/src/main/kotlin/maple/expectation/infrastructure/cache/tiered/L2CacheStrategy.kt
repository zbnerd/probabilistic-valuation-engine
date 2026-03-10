package maple.expectation.infrastructure.cache.tiered

/**
 * L2 Cache Strategy Interface (Issue #247: PostgreSQL L2 Migration)
 *
 * <h3>Purpose</h3>
 *
 * <p>Abstracts L2 cache implementation to allow pluggable storage backends.
 * Enables migration from Redis L2 to PostgreSQL L2 without modifying business logic.
 *
 * <h3>Design Pattern: Strategy Pattern</h3>
 *
 * <ul>
 *   <li>RedisL2Cache: Existing Redis-based implementation (via Spring Cache adapter)</li>
 *   <li>PostgresL2Cache: New PostgreSQL-based implementation (cache_storage table)</li>
 * </ul>
 *
 * <h3>Contract</h3>
 *
 * <p>All operations must be:
 * <ul>
 *   <li>Thread-safe for concurrent access</li>
 *   <li>Resilient to transient failures (graceful degradation)</li>
 *   <li>Type-safe through generic deserialization</li>
 * </ul>
 *
 * @see PostgresL2Cache
 */
interface L2CacheStrategy {

    /**
     * Retrieve a value from L2 cache
     *
     * @param key Cache key (format: {cacheName}:v1:{actualKey})
     * @param type Target type for deserialization
     * @return Cached value, or null if not found/expired
     */
    fun <T : Any> get(key: String, type: Class<T>): T?

    /**
     * Store a value in L2 cache
     *
     * @param key Cache key
     * @param value Value to serialize and store
     * @param ttlMinutes Time-to-live in minutes
     */
    fun put(key: String, value: Any, ttlMinutes: Long)

    /**
     * Remove a specific entry from L2 cache
     *
     * @param key Cache key to evict
     */
    fun evict(key: String)

    /**
     * Remove all entries for a specific cache name
     *
     * <p>Uses key prefix matching: {cacheName}:%
     *
     * @param cacheName Cache name (e.g., "equipment", "characterBasic")
     */
    fun evictAll(cacheName: String)

    /**
     * Check if L2 cache is healthy and operational
     *
     * @return true if cache is available, false otherwise
     */
    fun isHealthy(): Boolean = true
}
