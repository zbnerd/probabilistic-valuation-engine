package maple.calculator.cache

import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.atomic.LongAdder
import org.slf4j.LoggerFactory

/**
 * Caffeine-backed [OffHeapCacheBackend]. Heap-resident; the default and the
 * permanent fallback for Chronicle Map init failures.
 *
 * Concurrent-safe via Caffeine's internal striping. Counters use LongAdder
 * to avoid contention under load (4 concurrent chunk workers).
 */
class CaffeineCacheBackend<K : Any, V : Any>(
    private val config: CacheConfig,
) : OffHeapCacheBackend<K, V> {

    override val name: String = "caffeine"

    private val log = LoggerFactory.getLogger(CaffeineCacheBackend::class.java)

    private val cache = Caffeine.newBuilder()
        .maximumSize(config.maxEntries)
        .recordStats()
        .build<K, V>()

    private val hitsAdder = LongAdder()
    private val missesAdder = LongAdder()
    private val errorsAdder = LongAdder()

    override fun get(key: K): V? {
        val v = cache.getIfPresent(key)
        if (v == null) missesAdder.increment() else hitsAdder.increment()
        return v
    }

    override fun put(key: K, value: V) {
        try {
            cache.put(key, value)
        } catch (e: Exception) {
            errorsAdder.increment()
            log.error("CaffeineCacheBackend put failed: {}", e.message)
        }
    }

    override fun size(): Long = cache.estimatedSize()

    override fun stats(): CacheStats = CacheStats(
        size = cache.estimatedSize(),
        hits = hitsAdder.sum(),
        misses = missesAdder.sum(),
        errors = errorsAdder.sum(),
    )

    override fun close() {
        cache.invalidateAll()
        cache.cleanUp()
    }
}
