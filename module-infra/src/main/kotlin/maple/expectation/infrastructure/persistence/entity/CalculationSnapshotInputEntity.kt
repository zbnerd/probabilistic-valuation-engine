package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "calculation_snapshot_inputs")
class CalculationSnapshotInputEntity(
    @Id val inputId: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true) val jobId: UUID,
    @Column(nullable = false) val schemaVersion: Int = 1,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") val payload: String,
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
