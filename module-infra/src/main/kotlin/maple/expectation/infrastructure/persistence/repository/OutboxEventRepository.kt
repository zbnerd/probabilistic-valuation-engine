package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.OutboxEventEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEventEntity, UUID> {

    @Query("SELECT e FROM OutboxEventEntity e WHERE e.published = false ORDER BY e.createdAt")
    fun findUnpublished(limit: Int): List<OutboxEventEntity>

    @Modifying
    @Query("UPDATE OutboxEventEntity e SET e.published = true, e.publishedAt = :now WHERE e.eventId = :eventId")
    fun markPublished(@Param("eventId") eventId: UUID, @Param("now") now: OffsetDateTime = OffsetDateTime.now())

    @Modifying
    @Query("UPDATE OutboxEventEntity e SET e.publishAttempts = e.publishAttempts + 1 WHERE e.eventId = :eventId")
    fun incrementPublishAttempts(@Param("eventId") eventId: UUID)

    @Modifying
    @Query(value = "INSERT INTO outbox_events (event_id, event_type, job_id, payload) VALUES (:eventId, :eventType, :jobId, CAST(:payload AS jsonb)) ON CONFLICT (job_id, event_type) DO NOTHING", nativeQuery = true)
    fun insertIfAbsent(@Param("eventId") eventId: UUID, @Param("eventType") eventType: String, @Param("jobId") jobId: UUID, @Param("payload") payload: String?): Int
}
