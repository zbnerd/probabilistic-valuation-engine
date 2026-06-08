package maple.expectation.common.metrics

import maple.expectation.common.cache.LongCounter

/**
 * Technology-neutral metrics registry.
 *
 * <p>Adapters wrap Micrometer MeterRegistry, Dropwizard MetricRegistry, etc.
 */
interface MetricsRegistry {
    fun counter(name: String, tags: Map<String, String> = emptyMap()): LongCounter
    fun timer(name: String, tags: Map<String, String> = emptyMap()): Timer
}
