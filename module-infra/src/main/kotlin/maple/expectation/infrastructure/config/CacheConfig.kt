package maple.expectation.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.cache.RestrictedCacheManager
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.external.dto.v2.TotalExpectationResponse
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 캐시 설정 (P1-2: 외부화, P1-9: 중복 제거)
 *
 * <h4>P1-2: TTL/Size 하드코딩 → CacheProperties 외부화</h4>
 *
 * <p>specs.forEach()로 동적 등록하여 신규 캐시 추가 시 YAML만 변경
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties::class)
class CacheConfig {

  /**
   * TieredCacheManager 생성 및 의존성 주입
   *
   * <h4>Issue #148: 분산 락 및 메트릭 지원</h4>
   *
   * <h4>P0-4: lockWaitSeconds 외부 설정 (CacheProperties)</h4>
   *
   * @Primary 기존 @Cacheable 인프라 영향 최소화
   */
  @Bean
  @Primary
  fun cacheManager(
      connectionFactory: RedisConnectionFactory,
      executor: LogicExecutor,
      redissonClient: RedissonClient,
      meterRegistry: MeterRegistry,
      cacheProperties: CacheProperties): CacheManager {

    return TieredCacheManager(
        createL1Manager(cacheProperties),
        createL2Manager(connectionFactory, cacheProperties),
        executor,
        redissonClient,
        meterRegistry,
        cacheProperties.singleflight.lockWaitSeconds)
  }

  /**
   * TieredCacheManager bean (explicit type for injection)
   *
   * <p>Provides TieredCacheManager as a concrete bean type for components that need to inject it by
   * type rather than interface.
   */
  @Bean
  fun tieredCacheManager(
      connectionFactory: RedisConnectionFactory,
      executor: LogicExecutor,
      redissonClient: RedissonClient,
      meterRegistry: MeterRegistry,
      cacheProperties: CacheProperties): TieredCacheManager {

    return cacheManager(
        connectionFactory,
        executor,
        redissonClient,
        meterRegistry,
        cacheProperties) as TieredCacheManager
  }

  /**
   * L1 (Caffeine): 로컬 메모리 - Near Cache 전략
   *
   * <h4>P1-2: CacheProperties에서 동적 등록</h4>
   */
  private fun createL1Manager(cacheProperties: CacheProperties): CacheManager {
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
                  .build())
        }

    return l1Manager
  }

  /**
   * L2 (Redis): 분산 저장소 - 중앙 캐시 전략
   *
   * <h4>P1-2: CacheProperties에서 동적 등록</h4>
   *
   * <h4>Issue #240: cubeTrials 캐시 ClassCastException 수정</h4>
   *
   * <ul>
   *   <li>GenericJackson2JsonRedisSerializer는 Double 타입 보존 실패
   *   <li>JdkSerializationRedisSerializer 사용으로 타입 안전성 확보
   * </ul>
   */
  private fun createL2Manager(
      factory: RedisConnectionFactory,
      cacheProperties: CacheProperties): CacheManager {
    val defaultConfig =
        RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(15))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    StringRedisSerializer()))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    GenericJackson2JsonRedisSerializer()))

    val configurations = mutableMapOf<String, RedisCacheConfiguration>()

    cacheProperties
        .specs
        .forEach { (name, spec) ->
          val serializer = resolveSerializer(spec.l2Serializer)
          val config =
              RedisCacheConfiguration.defaultCacheConfig()
                  .entryTtl(Duration.ofMinutes(spec.l2TtlMinutes.toLong()))
                  .serializeKeysWith(
                      RedisSerializationContext.SerializationPair.fromSerializer(
                          StringRedisSerializer()))
                  .serializeValuesWith(
                      RedisSerializationContext.SerializationPair.fromSerializer(serializer))
          configurations[name] = config
        }

    return RedisCacheManager.builder(factory)
        .cacheDefaults(defaultConfig)
        .withInitialCacheConfigurations(configurations)
        .build()
  }

  /**
   * L2 직렬화 방식 결정 (P1-2)
   *
   * <ul>
   *   <li>json: GenericJackson2JsonRedisSerializer (기본)
   *   <li>jdk: JdkSerializationRedisSerializer (Double 타입 보존 등)
   * </ul>
   */
  private fun resolveSerializer(type: String): RedisSerializer<*> {
    return if ("jdk".equals(type, ignoreCase = true)) {
      RedisSerializer.java()
    } else {
      GenericJackson2JsonRedisSerializer()
    }
  }

  // ==================== Issue #158: Expectation 전용 캐시 인프라 ====================

  /**
   * Expectation 전용 Typed Serializer (M2 표준 - Spring Data Redis 3.x)
   *
   * <h4>설계 의도</h4>
   *
   * <ul>
   *   <li>@class 메타데이터 제거 → 5KB 압박 완화
   *   <li>타입 복원 100% 보장 (LinkedHashMap 복원 리스크 제거)
   *   <li>Spring Data Redis 3.x: ObjectMapper 생성자 직접 전달 (setObjectMapper deprecated 대응)
   * </ul>
   */
  @Bean
  @Qualifier("expectationCacheSerializer")
  fun expectationCacheSerializer(objectMapper: ObjectMapper): RedisSerializer<Any> {
    // Spring Data Redis 3.x: new Jackson2JsonRedisSerializer(ObjectMapper, Class)
    val serializer =
        Jackson2JsonRedisSerializer(objectMapper, TotalExpectationResponse::class.java)
    @Suppress("UNCHECKED_CAST", "rawtypes")
    val casted: RedisSerializer<Any> = serializer as RedisSerializer<Any>
    return casted
  }

  /**
   * Expectation 전용 L1 CacheManager (Caffeine)
   *
   * <p>P1-9: equipment L1은 cacheManager의 L1에서 동일 TTL/MaxSize 사용
   *
   * <p>Blocker C 해결: Expectation 경로에서 equipment L1-only가 실제로 동작하도록 equipment 캐시도 등록
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
            .build())

    // P1-9: equipment L1-only 캐시 (CacheProperties에서 TTL/Size 참조)
    val equipmentSpec = cacheProperties.specs["equipment"]
    if (equipmentSpec != null) {
      l1Manager.registerCustomCache(
          "equipment",
          Caffeine.newBuilder()
              .expireAfterWrite(equipmentSpec.l1TtlMinutes.toLong(), TimeUnit.MINUTES)
              .maximumSize(equipmentSpec.l1MaxSize.toLong())
              .recordStats()
              .build())
    }

    return l1Manager
  }

  /**
   * Expectation 전용 L2 CacheManager (Redis + RestrictedCacheManager) - P0-7/B3: equipment 구조적 봉쇄 -
   * expectationResult만 허용
   */
  @Bean(name = ["expectationL2CacheManager"])
  fun expectationL2CacheManager(
      connectionFactory: RedisConnectionFactory,
      @Qualifier("expectationCacheSerializer") serializer: RedisSerializer<Any>): CacheManager {

    val config =
        RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(30))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    StringRedisSerializer()))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(serializer))

    // RestrictedCacheManager가 기본 방어이므로 disableCreateOnMissingCache()는 제거 (버전 호환성)
    val delegate =
        RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .initialCacheNames(setOf("expectationResult"))
            .build()

    // 항상 RestrictedCacheManager로 래핑 (버전 무관하게 구조적 봉쇄)
    return RestrictedCacheManager(delegate, setOf("expectationResult"))
  }
}
