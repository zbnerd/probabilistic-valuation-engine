package maple.expectation.infrastructure.messaging

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RStream
import org.redisson.api.RedissonClient
import org.redisson.api.StreamAddArgs
import org.redisson.api.stream.StreamMessageId
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Redis Stream Publisher (Event Outbox Pattern)
 *
 * <h3>Event Flow</h3>
 *
 * <ol>
 *   <li>EventOutboxProcessor polls EventOutbox
 *   <li>Event published to target stream via XADD
 *   <li>Consumers process events from stream
 * </ol>
 *
 * <h3>Redis Stream Pattern</h3>
 *
 * <p>Uses Redisson RStream API to add entries to the stream. Each event contains:
 *
 * <ul>
 *   <li>eventId: Unique UUID for tracing
 *   <li>eventType: Event type identifier
 *   <li>payload: JSON-serialized event payload
 *   <li>timestamp: Event creation time
 * </ul>
 *
 * @see maple.expectation.infrastructure.event.outbox.EventOutboxProcessor
 */
@Component
class RedisStreamPublisher(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Max stream length (approximate trimming for memory management) */
        private const val MAX_STREAM_LENGTH = 10000L
    }

    /**
     * Publishes event to Redis Stream.
     *
     * <p>Follows V2 LikeSyncScheduler pattern: XADD to stream → async consumption by worker.
     *
     * @param streamName Target stream name (from EventOutbox.targetStream)
     * @param eventId Unique event identifier
     * @param eventType Event type identifier
     * @param payload JSON-serialized event payload
     * @return StreamMessageId if successful, null otherwise
     */
    fun publish(streamName: String, eventId: String, eventType: String, payload: String): StreamMessageId? {
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
    ): StreamMessageId {
        val stream: RStream<String, String> = redissonClient.getStream(streamName, StringCodec.INSTANCE)

        // Build event map for Redis Stream
        val eventMap = mapOf(
            "eventId" to eventId,
            "eventType" to eventType,
            "payload" to payload,
            "timestamp" to java.time.Instant.now().toEpochMilli().toString()
        )

        // XADD with approximate trimming for memory management
        val args = StreamAddArgs.entries(eventMap)
            .maxLen(MAX_STREAM_LENGTH) // Approximate trimming

        val messageId = stream.add(args)

        log.info(
            "[RedisStreamPublisher] Published event: stream={}, eventId={}, messageType={}",
            streamName, eventId, messageId
        )

        return messageId
    }

    /**
     * Create stream if not exists (for initialization purposes).
     *
     * <p>This is optional since Redis creates streams automatically on first XADD.
     * However, explicit creation can be useful for setting up consumer groups.
     *
     * @param streamName Stream name to create
     */
    fun ensureStreamExists(streamName: String) {
        val context = TaskContext.of("RedisStreamPublisher", "EnsureStream", streamName)

        executor.executeVoid(
            {
                val stream: RStream<String, String> = redissonClient.getStream(streamName, StringCodec.INSTANCE)

                // Check if stream exists (try to get info)
                val info = stream.size()
                if (info >= 0) {
                    log.info("[RedisStreamPublisher] Stream verified: stream={}, size={}", streamName, info)
                }
            },
            context
        )
    }

    /**
     * Get stream size for monitoring purposes.
     *
     * @param streamName Stream name to check
     * @return Current stream length (number of entries)
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
