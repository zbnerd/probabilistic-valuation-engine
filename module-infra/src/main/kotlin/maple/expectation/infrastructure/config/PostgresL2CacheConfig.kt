package maple.expectation.infrastructure.config

import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.cache.tiered.L2CacheStrategy
import maple.expectation.infrastructure.cache.tiered.PostgresL2CacheFactory
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.lock.LeaderElectionStrategy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

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
 *   <li>postgresL2CacheManager: Spring CacheManager implementation for L2</li>
 *   <li>l2CacheStrategy: Direct access to L2 operations</li>
 *   <li>cacheManager: Primary CacheManager with L1+L2 tiered caching (TieredCacheManager)</li>
 * </ul>
 *
 * @see PostgresL2Cache
 * @see CacheProperties.L2Implementation
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties::class)
@ConditionalOnProperty(
    name = ["cache.l2.impl"],
    havingValue = "postgres",
)
class PostgresL2CacheConfig {

    /**
     * L1 CacheManager (Caffeine)
     *
     * <p>Created locally since CaffeineOnlyCacheConfig
     disabled when L2 is enabled.
     */
    @Bean("l1CaffeineCacheManager")
    fun l1CacheManager(cacheProperties: CacheProperties): CacheManager {
        val l1Manager = CaffeineCacheManager()

        cacheProperties.specs.forEach { (name, spec) ->
            l1Manager.registerCustomCache(
                name,
                Caffeine.newBuilder()
                    .expireAfterWrite(spec.l1TtlMinutes.toLong(), TimeUnit.MINUTES)
                    .maximumSize(spec.l1MaxSize.toLong())
                    .recordStats()
                    .build(),
            )
        }
        return l1Manager
    }

    /**
     * PostgreSQL L2 CacheManager (Spring Cache compatible)
     *
     * <p>This bean is injected into TieredCacheManager as L2 backend.
     */
    @Bean("postgresL2CacheManager")
    fun postgresL2CacheManager(
        l2Strategy: L2CacheStrategy,
        executor: LogicExecutor,
        meterRegistry: MeterRegistry,
    ): CacheManager = PostgresL2CacheFactory(l2Strategy, executor, meterRegistry)

    /**
     * TieredCacheManager with PostgreSQL L2 backend (Primary CacheManager)
     *
     * <p>ADR-022: Replaces Redis-based TieredCacheManager with PostgreSQL version.
     * This is the primary CacheManager bean for the application.
     */
    @Bean("cacheManager")
    @Primary
    fun tieredCacheManager(
        @Qualifier("l1CaffeineCacheManager") l1Manager: CacheManager,
        @Qualifier("postgresL2CacheManager") l2Manager: CacheManager,
        executor: LogicExecutor,
        leaderElectionStrategy: LeaderElectionStrategy?,
        meterRegistry: MeterRegistry,
        cacheProperties: CacheProperties,
    ): TieredCacheManager = TieredCacheManager(
        l1Manager = l1Manager,
        l2Manager = l2Manager,
        executor = executor,
        leaderElectionStrategy = leaderElectionStrategy,
        meterRegistry = meterRegistry,
        lockWaitSeconds = cacheProperties.singleflight.lockWaitSeconds,
    )

    /**
     * Expectation 전용 L1 CacheManager (Caffeine)
     *
     * <p>Issue #589: Added for EquipmentCacheService and TotalExpectationCacheService.
     * Required when PostgresL2CacheConfig is active (cache.l2.impl=postgres).
     */
    @Bean(name = ["expectationL1CacheManager"])
    fun expectationL1CacheManager(cacheProperties: CacheProperties): CacheManager {
        val l1Manager = CaffeineCacheManager()

        // Expectation 결과 캐시
        l1Manager.registerCustomCache(
            "expectationResult",
            Caffeine.newBuilder()
                .expireAfterWrite(5L, TimeUnit.MINUTES)
                .maximumSize(1000L)
                .recordStats()
                .build(),
        )

        // equipment L1-only 캐시
        val equipmentSpec = cacheProperties.specs["equipment"]
        if (equipmentSpec != null) {
            l1Manager.registerCustomCache(
                "equipment",
                Caffeine.newBuilder()
                    .expireAfterWrite(equipmentSpec.l1TtlMinutes.toLong(), TimeUnit.MINUTES)
                    .maximumSize(equipmentSpec.l1MaxSize.toLong())
                    .recordStats()
                    .build(),
            )
        }

        return l1Manager
    }

    /**
     * Expectation 전용 L2 CacheManager (PostgreSQL)
     *
     * <p>Issue #589: Added for TotalExpectationCacheService.
     * Uses same PostgresL2CacheFactory pattern as postgresL2CacheManager.
     */
    @Bean(name = ["expectationL2CacheManager"])
    fun expectationL2CacheManager(
        l2Strategy: L2CacheStrategy,
        executor: LogicExecutor,
        meterRegistry: MeterRegistry,
    ): CacheManager = PostgresL2CacheFactory(l2Strategy, executor, meterRegistry)

    /**
     * Expectation 전용 ObjectMapper
     *
     * <p>Required by TotalExpectationCacheService for JSON serialization.
     */
    @Bean(name = ["expectationObjectMapper"])
    fun expectationObjectMapper(): com.fasterxml.jackson.databind.ObjectMapper = com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
