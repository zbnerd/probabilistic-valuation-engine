package maple.calculator.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import maple.calculator.cache.OffHeapCacheBackend
import maple.calculator.processor.ValuationCache
import org.springframework.stereotype.Component

/**
 * Prometheus metrics for the calculation cache backend (issue #1311, Phase 2).
 *
 * Reads from [OffHeapCacheBackend.stats] which returns an immutable snapshot.
 * Micrometer's [Gauge.builder] re-reads the supplier on each Prometheus scrape
 * so values are always current.
 *
 * Tag `cache={caffeine,chronicle}` lets us compare hit rates across backends
 * during the canary rollout (spec §7.2).
 */
@Component
class CacheMetrics(
    registry: MeterRegistry,
    valuationCache: ValuationCache,
) {
    private val backend: OffHeapCacheBackend<*, *> = valuationCache.backend()

    init {
        val cacheName = backend.name

        Gauge.builder("calculator_cache_size") { backend.size().toDouble() }
            .description("Current entries in the calculation cache")
            .tag("cache", cacheName)
            .register(registry)

        Gauge.builder("calculator_cache_hit_rate") {
            val s = backend.stats()
            s.hitRatePercent
        }
            .description("Cache hit rate (percent) since JVM start")
            .tag("cache", cacheName)
            .register(registry)

        Gauge.builder("calculator_cache_hits_total") { backend.stats().hits.toDouble() }
            .description("Cumulative cache hits since JVM start")
            .tag("cache", cacheName)
            .register(registry)

        Gauge.builder("calculator_cache_misses_total") { backend.stats().misses.toDouble() }
            .description("Cumulative cache misses since JVM start")
            .tag("cache", cacheName)
            .register(registry)

        Gauge.builder("calculator_cache_errors_total") { backend.stats().errors.toDouble() }
            .description("Cumulative cache backend errors since JVM start (per spec §5)")
            .tag("cache", cacheName)
            .register(registry)
    }
}
