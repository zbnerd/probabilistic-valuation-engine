package maple.expectation.infrastructure.persistence.repository

import java.util.UUID
import maple.expectation.infrastructure.persistence.entity.KafkaOutboxEventEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface KafkaOutboxEventRepository : JpaRepository<KafkaOutboxEventEntity, UUID> {

    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO kafka_outbox_events (id, event_type, aggregate_id, aggregate_type, topic, partition_key, payload, status, created_at, updated_at, next_attempt_at)
            VALUES (:id, :eventType, :aggregateId, :aggregateType, :topic, :partitionKey, CAST(:payload AS jsonb), 'PENDING', now(), now(), now())
            ON CONFLICT (event_type, aggregate_id) WHERE status IN ('PENDING', 'PUBLISHING', 'PUBLISHED') DO NOTHING
        """,
    )
    fun insertIfAbsent(
        @Param("id") id: UUID,
        @Param("eventType") eventType: String,
        @Param("aggregateId") aggregateId: UUID,
        @Param("aggregateType") aggregateType: String,
        @Param("topic") topic: String,
        @Param("partitionKey") partitionKey: String,
        @Param("payload") payload: String,
    ): Int

    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            UPDATE kafka_outbox_events
            SET status = 'PUBLISHED', published_at = now(), updated_at = now()
            WHERE id = :id AND status = 'PUBLISHING'
        """,
    )
    fun markPublished(@Param("id") id: UUID): Int

    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            UPDATE kafka_outbox_events
            SET status = 'PENDING', retry_count = retry_count + 1,
                next_attempt_at = now() + :retryDelayMs * interval '1 millisecond',
                last_error = :error, updated_at = now()
            WHERE id = :id
        """,
    )
    fun markRetryPending(@Param("id") id: UUID, @Param("error") error: String, @Param("retryDelayMs") retryDelayMs: Long): Int

    @Query(
        nativeQuery = true,
        value = "SELECT COUNT(*) FROM kafka_outbox_events WHERE status IN ('PENDING', 'PUBLISHING')",
    )
    fun countPending(): Long
}
