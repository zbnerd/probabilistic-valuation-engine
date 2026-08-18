package maple.calculator.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import maple.calculator.cache.CacheBackendFactory
import maple.calculator.cache.CacheConfig
import maple.calculator.cache.OffHeapCacheBackend
import maple.calculator.metrics.ValuationCacheMetrics
import maple.calculator.processor.ValuationCache
import maple.calculator.processor.ValuationCacheKey
import maple.expectation.core.calculation.ValuationKernel
import maple.expectation.core.calculation.ValuationResult
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring wiring for the off-heap cache backend (issue #1311, Phase 2).
 *
 * Profile switch: `calculator.cache.backend=caffeine|chronicle` (default caffeine).
 * `destroyMethod = "close"` ensures Spring calls [OffHeapCacheBackend.close]
 * on shutdown so Chronicle Map releases its mmap handle.
 *
 * **Current state (2026-06):** Chronicle Map stable does not support JDK 21.
 * ChronicleMapBackend is a stub that logs WARN + falls back to Caffeine.
 * Wiring still works — profile switch is honored, factory's catch-Error
 * fallback engages on real Chronicle failure (future when upstream ships JDK 21 support).
 */
@Configuration
class CacheBackendConfig {

    @Bean(destroyMethod = "close")
    fun cacheBackend(
        @Value("\${calculator.cache.backend:caffeine}") profile: String,
        @Value("\${calculator.cache.chronicle.path:/var/lib/calculator/chronicle-ocid}") path: String,
        @Value("\${calculator.cache.chronicle.max-entries:100000}") maxEntries: Long,
        objectMapper: ObjectMapper,
    ): OffHeapCacheBackend<ValuationCacheKey, ValuationResult> {
        val config = CacheConfig(maxEntries = maxEntries, chroniclePath = path)
        return CacheBackendFactory.create(
            profile,
            config,
            objectMapper,
            ValuationCacheKey::class.java,
            ValuationResult::class.java,
        )
    }

    @Bean
    fun valuationCacheMetrics(meterRegistry: MeterRegistry): ValuationCacheMetrics =
        ValuationCacheMetrics(meterRegistry)

    @Bean
    fun valuationCache(
        kernel: ValuationKernel,
        table: ProbabilityTableSnapshot,
        backend: OffHeapCacheBackend<ValuationCacheKey, ValuationResult>,
        metrics: ValuationCacheMetrics,
    ): ValuationCache = ValuationCache(kernel, table, backend, metrics)
}
