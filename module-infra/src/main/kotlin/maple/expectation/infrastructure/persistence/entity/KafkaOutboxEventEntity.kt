package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "kafka_outbox_events")
class KafkaOutboxEventEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val eventType: String,
    @Column(nullable = false) val aggregateId: UUID,
    @Column(nullable = false) val aggregateType: String,
    @Column(nullable = false) val topic: String,
    @Column(nullable = false) val partitionKey: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false) val payload: String,
    @Column(nullable = false) var status: String = "PENDING",
    @Column(nullable = false) var retryCount: Int = 0,
    @Column(nullable = false) var nextAttemptAt: OffsetDateTime = OffsetDateTime.now(),
    var publishedAt: OffsetDateTime? = null,
    var lastError: String? = null,
    @Column(nullable = false) val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Column(nullable = false) var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
