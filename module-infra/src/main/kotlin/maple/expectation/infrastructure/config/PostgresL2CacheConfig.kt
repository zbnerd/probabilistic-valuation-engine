package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.cache.tiered.L2CacheStrategy
import maple.expectation.infrastructure.cache.tiered.PostgresL2CacheFactory
import maple.expectation.infrastructure.executor.LogicExecutor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * PostgreSQL L2 Cache Configuration (Issue #247)
 *
 * <h3>Purpose</h3>
 *
 * <p>Configures PostgreSQL as L2 cache backend when `cache.l2.impl=postgres`.
 *
 * <h3>Activation</h3>
 *
 * <p>Only active when `cache.l2.impl=postgres` property is set.
 *
 * <h3>Beans</h3>
 *
 * <ul>
 *   <li>postgresL2CacheManager: Spring CacheManager implementation</li>
 *   <li>l2CacheStrategy: Direct access to L2 operations</li>
 * </ul>
 *
 * @see PostgresL2Cache
 * @see CacheProperties.L2Implementation
 */
@Configuration
@ConditionalOnProperty(
    name = ["cache.l2.impl"],
    havingValue = "postgres",
)
class PostgresL2CacheConfig {

    /**
     * PostgreSQL L2 CacheManager (Spring Cache compatible)
     *
     * <p>This bean is injected into TieredCacheManager as L2 backend.
     */
    @Bean
    fun postgresL2CacheManager(
        l2Strategy: L2CacheStrategy,
        executor: LogicExecutor,
        meterRegistry: MeterRegistry,
    ): CacheManager = PostgresL2CacheFactory(l2Strategy, executor, meterRegistry)
}
