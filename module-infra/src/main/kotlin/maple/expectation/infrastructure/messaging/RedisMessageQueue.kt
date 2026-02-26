package maple.expectation.infrastructure.messaging

import maple.expectation.core.port.out.MessageQueue
import org.redisson.api.RBlockingQueue
import org.redisson.api.RedissonClient

class RedisMessageQueue<T>(
    redissonClient: RedissonClient,
    queueName: String
) : MessageQueue<T> {

    private val queue: RBlockingQueue<T> = redissonClient.getBlockingQueue(queueName)

    override fun offer(message: T): Boolean = queue.offer(message)

    override fun poll(): T? {
        return try {
            queue.take()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    override fun size(): Int = queue.size
}
