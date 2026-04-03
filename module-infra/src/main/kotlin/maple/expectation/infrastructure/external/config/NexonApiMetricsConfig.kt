package maple.expectation.infrastructure.external.config

import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.impl.MetricsNexonApiClientWrapper
import maple.expectation.infrastructure.external.impl.RealNexonApiClient
import maple.expectation.infrastructure.ratelimit.NexonRateLimiter
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * 🔥 Nexon API Metrics Configuration
 *
 * <p>Purpose: Wire MetricsNexonApiClientWrapper into the application to instrument
 * all Nexon API calls without modifying existing code.
 *
 * <p>Architecture:
 * <pre>
 * Application Layer
 *   → ResilientNexonApiClient (circuit breaker, retry, bulkhead)
 *     → MetricsNexonApiClientWrapper (latency, error tracking) ← NEW
 *       → RealNexonApiClient (actual API calls)
 * </pre>
 *
 * <p>This configuration ensures metrics are collected for:
 * <ul>
 *   <li>Latency percentiles (p50, p95, p99)</li>
 *   <li>Error rate and error types (timeout, throttling, etc.)</li>
 *   <li>Request rate</li>
 * </ul>
 */
@Configuration
@Profile("!chaos")
class NexonApiMetricsConfig {

    /**
     * 🔥 Metrics Wrapper Bean
     *
     * <p>Wraps RealNexonApiClient to add instrumentation.
     *
     * <p>Used by ResilientNexonApiClient via @Qualifier
     *
     * @param realNexonApiClient The actual implementation
     * @param meterRegistry Micrometer registry
     * @return Instrumented client wrapper
     */
    @Bean
    fun metricsNexonApiClientWrapper(
        realNexonApiClient: RealNexonApiClient,
        meterRegistry: io.micrometer.core.instrument.MeterRegistry,
        rateLimiter: NexonRateLimiter,
    ): NexonApiClient {
        return MetricsNexonApiClientWrapper(realNexonApiClient, meterRegistry, rateLimiter)
    }
}
