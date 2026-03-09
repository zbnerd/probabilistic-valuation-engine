package maple.expectation.infrastructure.messaging

import java.util.function.BiConsumer
import maple.expectation.core.port.out.MessageTopic
import org.redisson.api.RedissonClient

/**
 * Redis-backed message topic implementation.
 *
 * <p>Infrastructure adapter for MessageTopic port. Implements pub/sub using Redisson RTopic.
 *
 * <p>NOTE: This is a generic class - do NOT annotate with @Component. Create specific bean
 * instances via @Configuration classes.
 *
 * @param T message type
 */
class RedisMessageTopic<T>(
    private val redissonClient: RedissonClient,
    private val topicName: String,
) : MessageTopic<T> {

    override fun addListener(messageType: Class<T>, listener: BiConsumer<String, T>): Int {
        val topic = redissonClient.getTopic(topicName)
        return topic.addListener(messageType) { channel, msg ->
            listener.accept(channel?.toString() ?: "", msg)
        }
    }

    override fun removeListener(listenerId: Int) {
        val topic = redissonClient.getTopic(topicName)
        topic.removeListener(listenerId)
    }

    override fun publish(channel: String, message: T) {
        val topic = redissonClient.getTopic("$topicName:$channel")
        topic.publish(message)
    }
}
