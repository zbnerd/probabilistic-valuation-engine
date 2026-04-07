package maple.expectation.infrastructure.batch

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap
import maple.expectation.infrastructure.cache.tiered.PostgresL2CacheStrategy
import maple.expectation.infrastructure.config.AdaptiveMicroBatchProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import org.springframework.cache.Cache
import org.springframework.cache.concurrent.ConcurrentMapCache
import org.springframework.stereotype.Service

/**
 * L2 Cache Micro-Batch Adapter (Issue #588, #599)
 *
 * <h3>Purpose</h3>
 * <p>Provides micro-batching for L2 cache lookups using AdaptiveMicroBatchUserService.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Per-type delegates stored in ConcurrentHashMap for thread safety</li>
 *   <li>L1 (Caffeine) + L2 (PostgreSQL) tiered caching</li>
 *   <li>Fast Lane for low load, Batch Lane for high load</li>
 * </ul>
 *
 * @see AdaptiveMicroBatchUserService
 * @see PostgresL2CacheStrategy
 */
@Service
class L2CacheMicroBatchAdapter(
    private val properties: AdaptiveMicroBatchProperties,
    private val logicExecutor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val l2Strategy: PostgresL2CacheStrategy,
) {
    /** Per-type delegate cache */
    private val delegates = ConcurrentHashMap<String, AdaptiveMicroBatchUserService<*>>()

    /** Per-type L1 caches */
    private val l1Caches = ConcurrentHashMap<String, Cache>()

    /**
     * Get cached value with adaptive micro-batching
     *
     * @param cacheKey Full cache key (including namespace)
     * @param type Value type class
     * @return Cached value or null
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(cacheKey: String, type: Class<T>): T? {
        val delegate = getOrCreateDelegate(type, cacheKey)
        return delegate.getByKey(cacheKey) as? T
    }

    private fun <T : Any> getOrCreateDelegate(type: Class<T>, cacheKey: String): AdaptiveMicroBatchUserService<*> {
        val typeName = type.name

        return delegates.computeIfAbsent(typeName) {
            val cache = l1Caches.computeIfAbsent(typeName) {
                ConcurrentMapCache("l2BatchCache-$typeName")
            }

            AdaptiveMicroBatchUserService(
                properties = properties,
                logicExecutor = logicExecutor,
                meterRegistry = meterRegistry,
                cache = cache,
                singleLoader = { key -> l2Strategy.get(key, type) },
                batchLoader = { keys -> l2Strategy.getAll(keys, type) },
            ).also { it.startBatchWorker() }
        }
    }
}
