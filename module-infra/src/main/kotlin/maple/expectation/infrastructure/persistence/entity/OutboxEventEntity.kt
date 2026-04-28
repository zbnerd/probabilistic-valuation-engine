package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "outbox_events")
class OutboxEventEntity(
    @Id val eventId: UUID = UUID.randomUUID(),
    @Column(nullable = false) val eventType: String,
    @Column(nullable = false) val jobId: UUID,
    @Column(columnDefinition = "jsonb") val payload: String? = null,
    @Column(nullable = false) val published: Boolean = false,
    @Column(nullable = false) val publishAttempts: Int = 0,
    @Column(nullable = false) val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val publishedAt: OffsetDateTime? = null
)
