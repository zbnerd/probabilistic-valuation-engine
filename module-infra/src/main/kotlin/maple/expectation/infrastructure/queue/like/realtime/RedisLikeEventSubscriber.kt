package maple.expectation.infrastructure.queue.like.realtime

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.LikeEventPublisher
import maple.expectation.core.port.out.LikeEventSubscriber
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RedissonClient
import org.redisson.api.listener.MessageListener

/**
 * RTopic 기반 좋아요 이벤트 구독자 (at-most-once)
 */
class RedisLikeEventSubscriber(
    private val redissonClient: RedissonClient,
    private val cacheManager: TieredCacheManager,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry
) : LikeEventSubscriber {

    companion object {
        private const val TOPIC_NAME = "like:events"
        private const val CACHE_NAME = "characterView"
    }

    private var listenerId: Int = -1

    override fun subscribe() {
        executor.executeVoid({
            val topic = redissonClient.getTopic(TOPIC_NAME)
            listenerId = topic.addListener(String::class.java, object : MessageListener<String> {
                override fun onMessage(channel: CharSequence, msg: String) {
                    handleEvent(msg)
                }
            })
        }, TaskContext.of("RedisLikeEventSubscriber", "Subscribe", TOPIC_NAME))
    }

    override fun unsubscribe() {
        if (listenerId >= 0) {
            executor.executeVoid({
                val topic = redissonClient.getTopic(TOPIC_NAME)
                topic.removeListener(listenerId)
            }, TaskContext.of("RedisLikeEventSubscriber", "Unsubscribe", TOPIC_NAME))
        }
    }

    private fun handleEvent(message: String) {
        val parts = message.split(":")
        if (parts.size >= 2) {
            val targetOcid = parts[1]
            cacheManager.evict(CACHE_NAME, targetOcid)
        }
    }

    override fun getTransportType(): LikeEventPublisher.TransportType =
        LikeEventPublisher.TransportType.RTOPIC
}
