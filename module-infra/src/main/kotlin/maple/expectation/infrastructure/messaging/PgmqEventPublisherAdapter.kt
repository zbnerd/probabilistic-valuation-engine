package maple.expectation.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.port.out.EventPublisher
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * PGMQ-based EventPublisher adapter.
 *
 * <p>Implements EventPublisher interface using PGMQ as the backing store.
 *
 * @see EventPublisher
 */
@Component
@ConditionalOnProperty(
    prefix = "app.event-publisher",
    name = ["type"],
    havingValue = "pgmq",
    matchIfMissing = false,
)
class PgmqEventPublisherAdapter(
    private val pgmqClient: PgmqClient,
    private val executor: LogicExecutor,
    private val objectMapper: ObjectMapper,
    @Qualifier("defaultAsyncExecutor") private val taskExecutor: Executor,
) : EventPublisher {

    override fun publish(topic: String, event: IntegrationEvent<*>) {
        val context = TaskContext.of("PgmqEventPublisher", "Publish", event.eventId)

        executor.executeVoid({
            val payload = objectMapper.writeValueAsString(event.payload)
            val message = StreamMessage(
                eventId = event.eventId,
                eventType = event.eventType,
                payload = payload,
                timestamp = Instant.now().toEpochMilli(),
            )

            val messageId = pgmqClient.send(topic, message)

            log.info(
                "[PgmqEventPublisher] Published event: topic={}, eventId={}, messageId={}",
                topic,
                event.eventId,
                messageId,
            )
        }, context)
    }

    override fun publishAsync(topic: String, event: IntegrationEvent<*>): CompletableFuture<Void> = CompletableFuture.runAsync({ publish(topic, event) }, taskExecutor)

    /**
     * Data class for stream message.
     */
    data class StreamMessage(
        val eventId: String,
        val eventType: String,
        val payload: String,
        val timestamp: Long,
    )

    companion object {
        private val log = LoggerFactory.getLogger(PgmqEventPublisherAdapter::class.java)
    }
}
