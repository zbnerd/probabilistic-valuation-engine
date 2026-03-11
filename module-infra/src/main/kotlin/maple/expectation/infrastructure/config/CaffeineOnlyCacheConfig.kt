package maple.expectation.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit
import maple.expectation.infrastructure.cache.CaffeineOnlyCacheManager
import maple.expectation.infrastructure.external.dto.v2.TotalExpectationResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializer

/**
 * Caffeine-only Cache Configuration (PostgreSQL-only mode)
 *
 * <h3>Purpose</h3>
 *
 * <p>Provides cache management when Redis is disabled.
 * Uses Caffeine for all caching needs without L2 distributed cache.
 *
 * <h3>Activation</h3>
 *
 * <p>Only active when `spring.data.redis.host` is not configured (Redis disabled).
 *
 * @see CacheProperties
 * @see CaffeineOnlyCacheManager
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties::class)
@ConditionalOnMissingBean(CacheConfig::class)
class CaffeineOnlyCacheConfig {

    /**
     * Primary CacheManager (Caffeine-only for PostgreSQL mode)
     */
    @Bean
    @Primary
    fun cacheManager(cacheProperties: CacheProperties): CacheManager {
        val l1Manager = CaffeineCacheManager()

        cacheProperties
            .specs
            .forEach { (name, spec) ->
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
     * Expectation 전용 L1 CacheManager (Caffeine)
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
     * Expectation 전용 Serializer (for compatibility with existing code)
     */
    @Bean
    @Qualifier("expectationCacheSerializer")
    fun expectationCacheSerializer(objectMapper: ObjectMapper): RedisSerializer<Any> {
        val serializer =
            Jackson2JsonRedisSerializer(objectMapper, TotalExpectationResponse::class.java)

        @Suppress("UNCHECKED_CAST", "rawtypes")
        val casted: RedisSerializer<Any> = serializer as RedisSerializer<Any>
        return casted
    }

    /**
     * L2 Cache Manager for Expectation (Caffeine-only fallback)
     */
    @Bean(name = ["expectationL2CacheManager"])
    fun expectationL2CacheManager(): CacheManager = CaffeineOnlyCacheManager()
}
