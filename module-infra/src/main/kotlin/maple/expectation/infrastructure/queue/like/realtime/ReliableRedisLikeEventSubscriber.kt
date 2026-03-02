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
import org.redisson.api.RReliableTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.Cache

/**
 * RReliableTopic 기반 좋아요 이벤트 구독자 (at-least-once)
 *
 * Issue #278: Scale-out 환경 실시간 좋아요 동기화
 */
class ReliableRedisLikeEventSubscriber(
    private val redissonClient: RedissonClient,
    private val cacheManager: TieredCacheManager,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.instance-id:\${HOSTNAME:unknown}}") private val instanceId: String
) : LikeEventSubscriber {

    private var topic: RReliableTopic? = null

    @PostConstruct
    override fun subscribe() {
        val context = TaskContext.of("LikePubSub", "Subscribe", instanceId)

        executor.executeVoid({
            topic = redissonClient.getReliableTopic(RedisKey.LIKE_EVENTS_RELIABLE_TOPIC.key)
            topic?.addListener(LikeEvent::class.java) { _, msg ->
                onEvent(msg)
            }
        }, context)
    }

    @PreDestroy
    override fun unsubscribe() {
        topic?.removeAllListeners()
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

    private fun evictL1Cache(userIgn: String) {
        val characterCache: Cache? = cacheManager.getL1CacheDirect("character")
        characterCache?.evict(userIgn)

        val basicCache: Cache? = cacheManager.getL1CacheDirect("characterBasic")
        basicCache?.evict(userIgn)

        val viewCache: Cache? = cacheManager.getL1CacheDirect("characterView")
        viewCache?.evict(userIgn)
    }

    private fun recordEventReceived() {
        meterRegistry.counter("like.event.received", "transport", "reliable").increment()
    }
}
