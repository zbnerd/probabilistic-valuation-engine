package maple.expectation.infrastructure.queue.like.realtime

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.LikeEventPublisher
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RedissonClient

/**
 * RReliableTopic 기반 좋아요 이벤트 발행자 (at-least-once)
 */
class ReliableRedisLikeEventPublisher(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry
) : LikeEventPublisher {

    companion object {
        private const val TOPIC_NAME = "like:events:reliable"
    }

    override fun publishLikeEvent(accountId: String, targetOcid: String, liked: Boolean) {
        executor.executeVoid({
            val topic = redissonClient.getReliableTopic(TOPIC_NAME)
            val message = "$accountId:$targetOcid:$liked"
            topic.publish(message)
        }, TaskContext.of("ReliableRedisLikeEventPublisher", "Publish", accountId))
    }

    override fun getTransportType(): LikeEventPublisher.TransportType =
        LikeEventPublisher.TransportType.RELIABLE_TOPIC
}
