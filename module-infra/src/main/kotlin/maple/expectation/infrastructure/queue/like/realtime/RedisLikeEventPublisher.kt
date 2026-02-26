package maple.expectation.infrastructure.queue.like.realtime

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.LikeEventPublisher
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RedissonClient

/**
 * RTopic 기반 좋아요 이벤트 발행자 (at-most-once)
 */
class RedisLikeEventPublisher(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry
) : LikeEventPublisher {

    companion object {
        private const val TOPIC_NAME = "like:events"
    }

    override fun publishLikeEvent(accountId: String, targetOcid: String, liked: Boolean) {
        executor.executeVoid({
            val topic = redissonClient.getTopic(TOPIC_NAME)
            val message = "$accountId:$targetOcid:$liked"
            topic.publish(message)
        }, TaskContext.of("RedisLikeEventPublisher", "Publish", accountId))
    }

    override fun getTransportType(): LikeEventPublisher.TransportType =
        LikeEventPublisher.TransportType.RTOPIC
}
