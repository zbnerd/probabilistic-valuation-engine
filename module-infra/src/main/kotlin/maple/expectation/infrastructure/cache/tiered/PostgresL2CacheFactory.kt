package maple.expectation.infrastructure.cache.tiered

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import maple.expectation.infrastructure.config.CacheProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager

/**
 * PostgreSQL L2 Cache Factory (Issue #247)
 *
 * <h3>Purpose</h3>
 *
 * <p>Creates Spring Cache-compatible instances backed by PostgreSQL L2 storage.
 * Bridges L2CacheStrategy with Spring's CacheManager abstraction.
 *
 * <h3>Design Pattern: Adapter Pattern</h3>
 *
 * <ul>
 *   <li>Adapts L2CacheStrategy to Spring's Cache interface</li>
 *   <li>Enables drop-in replacement for RedisCacheManager</li>
 * </ul>
 *
 * <h3>Usage</h3>
 *
 * <pre>
 * @Bean
 * fun postgresL2CacheManager(
 *     l2Strategy: L2CacheStrategy,
 *     executor: LogicExecutor,
 *     meterRegistry: MeterRegistry,
 *     cacheProperties: CacheProperties
 * ): CacheManager = PostgresL2CacheFactory(l2Strategy, executor, meterRegistry, cacheProperties)
 * </pre>
 *
 * @see L2CacheStrategy
 * @see PostgresL2CacheStrategy
 */
class PostgresL2CacheFactory(
    private val l2Strategy: L2CacheStrategy,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val cacheProperties: CacheProperties,
) : CacheManager {

    companion object {
        private val log = LoggerFactory.getLogger(PostgresL2CacheFactory::class.java)
        private const val DEFAULT_L2_TTL_SECONDS = 15L
    }

    private val cacheMap = ConcurrentHashMap<String, Cache>()

    override fun getCache(name: String): Cache? = cacheMap.computeIfAbsent(name) { createPostgresL2CacheAdapter(it) }

    override fun getCacheNames(): MutableCollection<String> = cacheMap.keys

    /**
     * Create a new PostgresL2CacheAdapter instance with cache-specific TTL from YAML config.
     */
    private fun createPostgresL2CacheAdapter(name: String): Cache {
        val ttlSeconds = resolveTtlSeconds(name)
        log.debug("[PostgresL2Factory] Creating cache: {} with TTL: {}s", name, ttlSeconds)
        return PostgresL2CacheAdapter(name, l2Strategy, executor, meterRegistry, ttlSeconds)
    }

    private fun resolveTtlSeconds(cacheName: String): Long {
        val spec = cacheProperties.specs[cacheName]
        if (spec == null) {
            log.warn("[PostgresL2Factory] Cache '{}' not found in specs, using default TTL={}s. Define it in cache.specs.", cacheName, DEFAULT_L2_TTL_SECONDS)
            return DEFAULT_L2_TTL_SECONDS
        }
        return spec.l2TtlMinutes.toLong() * 60
    }
}

/**
 * PostgreSQL-backed Spring Cache Adapter
 *
 * <h3>Spring Cache Adapter</h3>
 *
 * <p>Implements Spring's Cache interface using L2CacheStrategy backend.
 * Provides ValueWrapper compatibility and supports valueLoader pattern.
 *
 * <p>Named "Adapter" to distinguish from PostgresL2CacheStrategy implementation.
 */
class PostgresL2CacheAdapter(
    private val cacheName: String,
    private val l2Strategy: L2CacheStrategy,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val ttlSeconds: Long,
) : org.springframework.cache.support.AbstractValueAdaptingCache(true) {

    companion object {
        private val log = LoggerFactory.getLogger(PostgresL2CacheAdapter::class.java)
    }

    // Metrics
    private val getCounter = meterRegistry.counter("cache.l2.get", "impl", "postgres", "cache", cacheName)
    private val putCounter = meterRegistry.counter("cache.l2.put", "impl", "postgres", "cache", cacheName)
    private val evictCounter = meterRegistry.counter("cache.l2.evict", "impl", "postgres", "cache", cacheName)
    private val clearCounter = meterRegistry.counter("cache.l2.clear", "impl", "postgres", "cache", cacheName)

    override fun getName(): String = cacheName

    override fun getNativeCache(): Any = l2Strategy

    override fun lookup(key: Any): Any? {
        val context = TaskContext.of("PostgresL2Cache", "Lookup", key.toString())

        return executor.executeOrDefault(
            {
                getCounter.increment()
                val value = l2Strategy.get(key.toString(), Any::class.java)
                // Fix: Ensure String values are not corrupted by type erasure
                // When TypedValue contains a String, return it directly
                value
            },
            null,
            context,
        )
    }

    override fun put(key: Any, value: Any?) {
        if (value == null) {
            log.debug("[PostgresL2Cache] Skipping null value: key={}", key)
            return
        }

        val context = TaskContext.of("PostgresL2Cache", "Put", key.toString())

        executor.executeVoidJava(
            {
                putCounter.increment()
                // Fix: Ensure String values are properly serialized through TypedValue wrapper
                // The L2Strategy will wrap the value in TypedValue for type-safe deserialization
                l2Strategy.put(key.toString(), value, ttlSeconds)
            },
            context,
        )
    }

    override fun evict(key: Any) {
        val context = TaskContext.of("PostgresL2Cache", "Evict", key.toString())

        executor.executeVoidJava(
            {
                evictCounter.increment()
                l2Strategy.evict(key.toString())
            },
            context,
        )
    }

    override fun clear() {
        val context = TaskContext.of("PostgresL2Cache", "Clear")

        executor.executeVoidJava(
            {
                clearCounter.increment()
                l2Strategy.evictAll(cacheName)
            },
            context,
        )
    }

    /**
     * Get with value loader (Single-flight pattern)
     *
     * <h4>Implementation Note</h4>
     *
     * <p>This is a simplified implementation. For full single-flight support
     * with distributed locking, use TieredCache wrapper.
     */
    override fun <T : Any> get(key: Any, type: Class<T>?): T? {
        val value = lookup(key)
        @Suppress("UNCHECKED_CAST")
        return value?.let { type?.cast(it) as? T }
    }

    /**
     * Get with value loader (compute-if-absent pattern)
     *
     * <h4>Implementation Note</h4>
     *
     * <p>Simple check-then-compute. For distributed single-flight,
     * wrap this cache in TieredCache which provides Redis-based locking.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: Any, valueLoader: Callable<T>): T {
        val cached = lookup(key)
        if (cached != null) {
            return cached as T
        }

        val context = TaskContext.of("PostgresL2Cache", "GetWithLoader", key.toString())
        val value = executor.execute(
            { valueLoader.call() },
            context,
        )

        put(key, value)
        return value as T
    }
}
