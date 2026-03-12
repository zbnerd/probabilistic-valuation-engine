package maple.expectation.infrastructure.messaging

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * PGMQ-based Stream Publisher (Redis Stream 대체)
 *
 * <h3>Redis Stream → PGMQ Migration</h3>
 * <p>Redis Stream 대신 PostgreSQL PGMQ를 사용하여 이벤트를 발행합니다.
 *
 * <h3>장점</h3>
 * <ul>
 *   <li>PostgreSQL 통합: 단일 DB로 관리
 *   <li>트랜잭션 지원: DB 작업과 메시지 발행이 원자적
 *   <li>운영 단순화: Redis 의존성 제거
 * </ul>
 *
 * @see PgmqClient
 * @see maple.expectation.infrastructure.event.outbox.EventOutboxProcessor
 */
@Component
@ConditionalOnProperty(
    prefix = "app.event-publisher",
    name = ["type"],
    havingValue = "pgmq",
    matchIfMissing = false,
)
class PgmqStreamPublisher(
    private val pgmqClient: PgmqClient,
    private val executor: LogicExecutor,
) {

    companion object {
        private val log = LoggerFactory.getLogger(PgmqStreamPublisher::class.java)
    }

    /**
     * Publishes event to PGMQ queue.
     *
     * @param streamName Target queue name (maps to PGMQ queue)
     * @param eventId Unique event identifier
     * @param eventType Event type identifier
     * @param payload JSON-serialized event payload
     * @return Message ID if successful, null otherwise
     */
    fun publish(streamName: String, eventId: String, eventType: String, payload: String): Long? {
        val context = TaskContext.of("PgmqStreamPublisher", "Publish", eventId)

        return executor.executeOrDefault(
            { publishInternal(streamName, eventId, eventType, payload) },
            null,
            context,
        )
    }

    /** Internal publish logic */
    private fun publishInternal(
        streamName: String,
        eventId: String,
        eventType: String,
        payload: String,
    ): Long {
        val message = StreamMessage(
            eventId = eventId,
            eventType = eventType,
            payload = payload,
            timestamp = java.time.Instant.now().toEpochMilli(),
        )

        val messageId = pgmqClient.send(streamName, message)

        log.info(
            "[PgmqStreamPublisher] Published event: queue={}, eventId={}, messageId={}",
            streamName,
            eventId,
            messageId,
        )

        return messageId
    }

    /**
     * Data class for stream message.
     */
    data class StreamMessage(
        val eventId: String,
        val eventType: String,
        val payload: String,
        val timestamp: Long,
    )
}
