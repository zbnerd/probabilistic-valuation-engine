package maple.expectation.infrastructure.queue.like.realtime

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import maple.expectation.core.dto.like.LikeEvent
import maple.expectation.core.port.out.LikeEventSubscriber
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.queue.RedisKey
import org.redisson.api.RedissonClient
import org.redisson.api.listener.MessageListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.Cache

/**
 * RTopic 기반 좋아요 이벤트 구독자 (at-most-once)
 *
 * Issue #278: Scale-out 환경 실시간 좋아요 동기화
 *
 * 다른 인스턴스에서 발행한 이벤트를 수신하여 L1(Caffeine) 캐시 무효화
 *
 * 캐시 무효화 전략 (5-Agent Council 합의):
 * - Pub/Sub 수신 → L1 즉시 evict
 * - TTL(5분)은 Fallback용 (Pub/Sub 유실 시)
 * - 자기 자신이 발행한 이벤트는 무시 (Self-skip)
 */
class RedisLikeEventSubscriber(
    private val redissonClient: RedissonClient,
    private val cacheManager: TieredCacheManager,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.instance-id:\${HOSTNAME:unknown}}") private val instanceId: String
) : LikeEventSubscriber {

    private var listenerId: Int = -1

    @PostConstruct
    override fun subscribe() {
        val context = TaskContext.of("LikePubSub", "Subscribe", instanceId)

        executor.executeVoid({
            val topic = redissonClient.getTopic(RedisKey.LIKE_EVENTS_TOPIC.key)
            listenerId = topic.addListener(LikeEvent::class.java) { _, msg ->
                onEvent(msg)
            }
        }, context)
    }

    @PreDestroy
    override fun unsubscribe() {
        if (listenerId >= 0) {
            val context = TaskContext.of("LikePubSub", "Unsubscribe", instanceId)
            executor.executeVoid({
                val topic = redissonClient.getTopic(RedisKey.LIKE_EVENTS_TOPIC.key)
                topic.removeListener(listenerId)
            }, context)
        }
    }

    override fun onEvent(event: LikeEvent) {
        // Self-skip: 자기가 발행한 이벤트는 무시
        if (instanceId == event.sourceInstanceId) {
            return
        }

        val context = TaskContext.of("LikePubSub", "OnEvent", event.userIgn)

        executor.executeVoid({
            evictL1Cache(event.userIgn)
            recordEventReceived()
        }, context)
    }

    /**
     * L1 캐시 무효화 (character 캐시)
     *
     * TieredCacheManager.getL1CacheDirect()로 L1만 직접 evict
     * L2(Redis)는 모든 인스턴스가 공유하므로 evict 불필요
     */
    private fun evictL1Cache(userIgn: String) {
        // character 캐시의 L1만 evict (GameCharacter 엔티티)
        val characterCache: Cache? = cacheManager.getL1CacheDirect("character")
        characterCache?.evict(userIgn)

        // characterBasic 캐시도 evict (기본 정보)
        val basicCache: Cache? = cacheManager.getL1CacheDirect("characterBasic")
        basicCache?.evict(userIgn)

        // characterView 캐시도 evict
        val viewCache: Cache? = cacheManager.getL1CacheDirect("characterView")
        viewCache?.evict(userIgn)
    }

    // ==================== Metrics ====================

    private fun recordEventReceived() {
        meterRegistry.counter("like.event.received").increment()
    }
}
