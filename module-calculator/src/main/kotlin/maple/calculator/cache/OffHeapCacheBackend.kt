package maple.calculator.cache

/**
 * Off-heap cache backend abstraction. Two impls: [CaffeineCacheBackend]
 * (heap, default) and [ChronicleMapBackend] (off-heap, opt-in).
 *
 * Implementations must:
 * - be safe for concurrent get/put from multiple threads
 * - never block callers on a missing/corrupt file (fail-soft per spec §5)
 * - report size/hits/misses/errors via [stats]
 */
interface OffHeapCacheBackend<K : Any, V : Any> : AutoCloseable {

    /** Returns the cached value for [key], or null on miss. Increments hit/miss counters. */
    fun get(key: K): V?

    /** Stores [value] under [key]. On error: logs, increments error counter, does NOT throw. */
    fun put(key: K, value: V)

    /** Current number of entries. O(1). */
    fun size(): Long

    /** Cumulative stats snapshot. */
    fun stats(): CacheStats

    /** Name of the backend ("caffeine" or "chronicle"). Used as Prometheus label value. */
    val name: String

    /** Release native resources. Best-effort; never throws. */
    override fun close()
}
