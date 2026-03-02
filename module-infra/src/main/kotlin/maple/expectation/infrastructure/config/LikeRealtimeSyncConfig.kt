package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.LikeEventPublisher
import maple.expectation.core.port.out.LikeEventSubscriber
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.queue.like.realtime.RedisLikeEventPublisher
import maple.expectation.infrastructure.queue.like.realtime.RedisLikeEventSubscriber
import maple.expectation.infrastructure.queue.like.realtime.ReliableRedisLikeEventPublisher
import maple.expectation.infrastructure.queue.like.realtime.ReliableRedisLikeEventSubscriber
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 좋아요 실시간 동기화 설정 (Issue #278)
 *
 * Scale-out 환경 Pub/Sub 설정
 *
 * like.realtime.enabled=true 시 활성화
 *
 * like.realtime.transport로 RTopic / RReliableTopic 전환
 *
 * 5-Agent Council 합의:
 * - Blue (Architect): Strategy 패턴으로 구현체 교체 가능
 * - Red (SRE): ConditionalOnProperty로 런타임 비활성화 지원
 * - Green (Performance): 단일 토픽 인스턴스 재사용
 * - Yellow (QA): RTopic/RReliableTopic 교차 통신 불가 → Blue-Green 배포 필수
 * - Purple (Data): RReliableTopic at-least-once + L1 eviction idempotent → 중복 수신 무해
 *
 * Transport 전환 전략:
 * ```
 * like.realtime.transport=rtopic           → 기존 RTopic (at-most-once, 기본값)
 * like.realtime.transport=reliable-topic   → RReliableTopic (at-least-once)
 * ```
 */
@Configuration
@ConditionalOnProperty(
    name = ["like.realtime.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class LikeRealtimeSyncConfig(
    @Value("\${app.instance-id:\${HOSTNAME:unknown}}") private val instanceId: String
) {

    private val log = LoggerFactory.getLogger(LikeRealtimeSyncConfig::class.java)

    // ==================== RTopic (기존, 기본값) ====================

    /**
     * RTopic 기반 설정 (at-most-once, 기본값)
     *
     * like.realtime.transport=rtopic 또는 미설정 시 활성화
     */
    @Configuration
    @ConditionalOnProperty(
        name = ["like.realtime.transport"],
        havingValue = "rtopic",
        matchIfMissing = true
    )
    class RTopicConfig(
        private val redissonClient: RedissonClient,
        private val cacheManager: TieredCacheManager,
        private val executor: LogicExecutor,
        private val meterRegistry: MeterRegistry,
        private val instanceId: String
    ) {

        private val log = LoggerFactory.getLogger(RTopicConfig::class.java)

        @Bean
        fun likeEventPublisher(): LikeEventPublisher {
            log.info("[LikeRealtimeSyncConfig] Creating RTopic LikeEventPublisher bean (at-most-once)")
            return RedisLikeEventPublisher(redissonClient, executor, meterRegistry, instanceId)
        }

        @Bean
        fun likeEventSubscriber(): LikeEventSubscriber {
            log.info("[LikeRealtimeSyncConfig] Creating RTopic LikeEventSubscriber bean (at-most-once)")
            return RedisLikeEventSubscriber(redissonClient, cacheManager, executor, meterRegistry, instanceId)
        }
    }

    // ==================== RReliableTopic (Issue #278 P0) ====================

    /**
     * RReliableTopic 기반 설정 (at-least-once)
     *
     * like.realtime.transport=reliable-topic 시 활성화
     *
     * 주의: Blue-Green 배포 필수
     *
     * RTopic과 RReliableTopic은 Redis 구조가 다르므로 롤링 배포 시 교차 통신 불가. 전체 동시 전환 필요.
     */
    @Configuration
    @ConditionalOnProperty(name = ["like.realtime.transport"], havingValue = "reliable-topic")
    class ReliableTopicConfig(
        private val redissonClient: RedissonClient,
        private val cacheManager: TieredCacheManager,
        private val executor: LogicExecutor,
        private val meterRegistry: MeterRegistry,
        private val instanceId: String
    ) {

        private val log = LoggerFactory.getLogger(ReliableTopicConfig::class.java)

        @Bean
        fun likeEventPublisher(): LikeEventPublisher {
            log.info("[LikeRealtimeSyncConfig] Creating RReliableTopic LikeEventPublisher bean (at-least-once)")
            return ReliableRedisLikeEventPublisher(redissonClient, executor, meterRegistry, instanceId)
        }

        @Bean
        fun likeEventSubscriber(): LikeEventSubscriber {
            log.info("[LikeRealtimeSyncConfig] Creating RReliableTopic LikeEventSubscriber bean (at-least-once)")
            return ReliableRedisLikeEventSubscriber(redissonClient, cacheManager, executor, meterRegistry, instanceId)
        }
    }
}
