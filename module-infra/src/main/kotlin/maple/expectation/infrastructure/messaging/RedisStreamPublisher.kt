package maple.expectation.infrastructure.messaging

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RStream
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Redis Stream Publisher (Event Outbox Pattern)
 *
 * @see maple.expectation.infrastructure.event.outbox.EventOutboxProcessor
 */
@Component
class RedisStreamPublisher(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Publishes event to Redis Stream.
     *
     * @param streamName Target stream name
     * @param eventId Unique event identifier
     * @param eventType Event type identifier
     * @param payload JSON-serialized event payload
     * @return Stream message ID if successful, null otherwise
     */
    fun publish(streamName: String, eventId: String, eventType: String, payload: String): String? {
        val context = TaskContext.of("RedisStreamPublisher", "Publish", eventId)

        return executor.executeOrDefault(
            { publishInternal(streamName, eventId, eventType, payload) },
            null,
            context
        )
    }

    /** Internal publish logic with exception handling */
    private fun publishInternal(
        streamName: String,
        eventId: String,
        eventType: String,
        payload: String
    ): String {
        val stream: RStream<String, String> = redissonClient.getStream(streamName, StringCodec.INSTANCE)

        // Build event map for Redis Stream
        val eventMap = mapOf(
            "eventId" to eventId,
            "eventType" to eventType,
            "payload" to payload,
            "timestamp" to java.time.Instant.now().toEpochMilli().toString()
        )

        // XADD to stream
        val messageId = stream.add(eventMap)

        log.info(
            "[RedisStreamPublisher] Published event: stream={}, eventId={}, messageId={}",
            streamName, eventId, messageId
        )

        return messageId.toString()
    }

    /**
     * Get stream size for monitoring purposes.
     *
     * @param streamName Stream name to check
     * @return Current stream length
     */
    fun getStreamSize(streamName: String): Long {
        val context = TaskContext.of("RedisStreamPublisher", "StreamSize", streamName)

        return executor.executeOrDefault(
            {
                val stream: RStream<String, String> = redissonClient.getStream(streamName, StringCodec.INSTANCE)
                stream.size()
            },
            0L,
            context
        )
    }
}
