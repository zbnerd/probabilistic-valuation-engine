package maple.expectation.infrastructure.messaging

import java.util.UUID
import maple.expectation.infrastructure.config.KafkaPipelineProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.KafkaOutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "app.kafka.pipeline", name = ["enabled"], havingValue = "true")
class KafkaOutboxPublisher(
    private val jdbcTemplate: JdbcTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val outboxRepository: KafkaOutboxEventRepository,
    private val properties: KafkaPipelineProperties,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(KafkaOutboxPublisher::class.java)

    data class ClaimedEvent(
        val id: UUID,
        val topic: String,
        val partitionKey: String,
        val payload: String,
    )

    @Scheduled(fixedDelayString = "\${app.kafka.pipeline.outbox.poll-interval-ms:1000}")
    fun publishPendingEvents() {
        executor.executeVoid(
            { publishBatch() },
            TaskContext.of("KafkaOutboxPublisher", "PublishBatch"),
        )
    }

    private fun publishBatch() {
        val events = claimBatch()
        if (events.isEmpty()) return

        log.debug("[KafkaOutboxPublisher] Claimed {} events", events.size)

        for (event in events) {
            publishSingleAsync(event)
        }
    }

    private fun publishSingleAsync(event: ClaimedEvent) {
        val future = kafkaTemplate.send(event.topic, event.partitionKey, event.payload)
        future.whenComplete { _, ex ->
            executor.executeVoid(
                {
                    if (ex == null) {
                        handlePublishSuccess(event)
                    } else {
                        handlePublishFailure(event, ex)
                    }
                },
                TaskContext.of("KafkaOutboxPublisher", "PublishCallback", event.topic),
            )
        }
    }

    private fun handlePublishSuccess(event: ClaimedEvent) {
        outboxRepository.markPublished(event.id)
        log.debug("[KafkaOutboxPublisher] Published event {} to {}", event.id, event.topic)
    }

    private fun handlePublishFailure(event: ClaimedEvent, ex: Throwable) {
        val errorMsg = ex.message ?: "Unknown error"
        outboxRepository.markRetryPending(event.id, errorMsg, properties.outbox.retryDelayMs)
        log.warn("[KafkaOutboxPublisher] Publish failed for event {} to {}: {}", event.id, event.topic, errorMsg)
    }

    private fun claimBatch(): List<ClaimedEvent> {
        val batchSize = properties.outbox.batchSize
        val sql = """
            WITH picked AS (
                SELECT id FROM kafka_outbox_events
                WHERE status = 'PENDING' AND next_attempt_at <= now()
                ORDER BY created_at LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE kafka_outbox_events e
            SET status = 'PUBLISHING', updated_at = now()
            FROM picked WHERE e.id = picked.id
            RETURNING e.id, e.topic, e.partition_key, e.payload
        """.trimIndent()

        return jdbcTemplate.query(sql, { rs, _ ->
            ClaimedEvent(
                id = rs.getObject("id", UUID::class.java),
                topic = rs.getString("topic"),
                partitionKey = rs.getString("partition_key"),
                payload = rs.getString("payload"),
            )
        }, batchSize)
    }
}
