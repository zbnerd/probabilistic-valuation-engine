package maple.expectation.infrastructure.persistence.repository

import java.time.OffsetDateTime
import java.util.UUID
import maple.expectation.infrastructure.persistence.entity.OutboxEventEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface OutboxEventRepository : JpaRepository<OutboxEventEntity, UUID> {

    @Query("SELECT e FROM OutboxEventEntity e WHERE e.published = false ORDER BY e.createdAt")
    fun findUnpublished(limit: Int, pageable: org.springframework.data.domain.Pageable): List<OutboxEventEntity>

    @Modifying
    @Query("UPDATE OutboxEventEntity e SET e.published = true, e.publishedAt = :now WHERE e.eventId = :eventId")
    fun markPublished(@Param("eventId") eventId: UUID, @Param("now") now: OffsetDateTime = OffsetDateTime.now())

    @Modifying
    @Query("UPDATE OutboxEventEntity e SET e.published = true, e.publishedAt = :now WHERE e.eventId IN :eventIds")
    fun markAllPublished(@Param("eventIds") eventIds: List<UUID>, @Param("now") now: OffsetDateTime = OffsetDateTime.now())

    @Modifying
    @Query("UPDATE OutboxEventEntity e SET e.publishAttempts = e.publishAttempts + 1 WHERE e.eventId = :eventId")
    fun incrementPublishAttempts(@Param("eventId") eventId: UUID)

    @Modifying
    @Query(value = "INSERT INTO outbox_events (event_id, event_type, job_id, payload, published, publish_attempts, created_at) VALUES (:eventId, :eventType, :jobId, CAST(:payload AS jsonb), false, 0, now()) ON CONFLICT (job_id, event_type) DO NOTHING", nativeQuery = true)
    fun insertIfAbsent(@Param("eventId") eventId: UUID, @Param("eventType") eventType: String, @Param("jobId") jobId: UUID, @Param("payload") payload: String?): Int
}
