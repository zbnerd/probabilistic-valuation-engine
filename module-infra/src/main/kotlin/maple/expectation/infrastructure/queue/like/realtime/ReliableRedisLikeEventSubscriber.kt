package maple.expectation.infrastructure.queue.like.realtime

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.LikeEventPublisher
import maple.expectation.core.port.out.LikeEventSubscriber
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RedissonClient
import org.redisson.api.RReliableTopic
import org.redisson.api.listener.MessageListener
import org.springframework.cache.CacheManager

/**
 * RReliableTopic 기반 좋아요 이벤트 구독자 (at-least-once)
 */
class ReliableRedisLikeEventSubscriber(
    private val redissonClient: RedissonClient,
    private val cacheManager: CacheManager,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry
) : LikeEventSubscriber {

    companion object {
        private const val TOPIC_NAME = "like:events:reliable"
    }

    private var topic: RReliableTopic? = null

    override fun subscribe() {
        executor.executeVoid({
            topic = redissonClient.getReliableTopic(TOPIC_NAME)
            topic?.addListener(String::class.java, object : MessageListener<String> {
                override fun onMessage(channel: CharSequence, msg: String) {
                    handleEvent(msg)
                }
            })
        }, TaskContext.of("ReliableRedisLikeEventSubscriber", "Subscribe", TOPIC_NAME))
    }

    override fun unsubscribe() {
        topic?.removeAllListeners()
    }

    private fun handleEvent(message: String) {
        val parts = message.split(":")
        if (parts.size >= 2) {
            val targetOcid = parts[1]
            cacheManager.getCache("characterView")?.evict(targetOcid)
        }
    }

    override fun getTransportType(): LikeEventPublisher.TransportType =
        LikeEventPublisher.TransportType.RELIABLE_TOPIC
}
