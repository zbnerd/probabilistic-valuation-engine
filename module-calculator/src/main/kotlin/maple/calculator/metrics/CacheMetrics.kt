package maple.calculator.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import maple.calculator.processor.CalculationCache
import org.springframework.stereotype.Component

/**
 * Prometheus gauges for the Caffeine [CalculationCache].
 *
 * Caffeine's `recordStats()` produces a live snapshot on every call;
 * Micrometer's [Gauge.builder] re-reads the supplier on each Prometheus scrape, so
 * the metrics are always current without a scheduled task. Cache hit rate is the
 * primary signal: a low rate means the Caffeine max-size (100K) is too small for
 * the working set, or items are random enough that L2 (PostgreSQL UNLOGGED)
 * is needed for cross-JVM caching.
 */
@Component
class CacheMetrics(
    registry: MeterRegistry,
    private val calculationCache: CalculationCache,
) {
    private val cache = calculationCache.cache()

    init {
        Gauge.builder("calculator_cache_size") { cache.estimatedSize().toDouble() }
            .description("Current entries in the Caffeine calculation cache")
            .register(registry)

        Gauge.builder("calculator_cache_hit_rate") {
                val s = cache.stats()
                if (s.requestCount() == 0L) 0.0 else s.hitRate() * 100.0
            }
            .description("Cache hit rate (percent) since JVM start")
            .register(registry)

        Gauge.builder("calculator_cache_hits_total") { cache.stats().hitCount().toDouble() }
            .description("Cumulative cache hits since JVM start")
            .register(registry)

        Gauge.builder("calculator_cache_misses_total") { cache.stats().missCount().toDouble() }
            .description("Cumulative cache misses since JVM start")
            .register(registry)

        Gauge.builder("calculator_cache_evictions_total") { cache.stats().evictionCount().toDouble() }
            .description("Cumulative cache evictions since JVM start")
            .register(registry)
    }
}
