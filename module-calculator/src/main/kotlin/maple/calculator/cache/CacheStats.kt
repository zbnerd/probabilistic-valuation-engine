package maple.calculator.cache

/**
 * Immutable snapshot of cache backend counters.
 * Sourced from [OffHeapCacheBackend.stats] on demand; Prometheus scrape reads
 * this supplier so values are always current.
 */
data class CacheStats(
    val size: Long,
    val hits: Long,
    val misses: Long,
    val errors: Long,
) {
    val hitRatePercent: Double
        get() = if (hits + misses == 0L) 0.0 else hits.toDouble() / (hits + misses) * 100.0
}
