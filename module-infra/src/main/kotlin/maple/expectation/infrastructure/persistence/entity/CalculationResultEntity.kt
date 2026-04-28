package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "calculation_results")
class CalculationResultEntity(
    @Id val resultId: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true) val jobId: UUID,
    val characterClass: String? = null,
    @Column(nullable = false) val presetNo: Int = 1,
    @Column(nullable = false) val schemaVersion: Int = 1,
    @Column(nullable = false) val contentType: String = "application/json",
    @Column(nullable = false) val contentEncoding: String = "gzip",
    @Column(columnDefinition = "bytea") val responseBody: ByteArray = ByteArray(0),
    val originalSize: Int = 0,
    val compressedSize: Int = 0,
    val hash: String? = null,
    @Column(nullable = false) val status: String = "SUCCESS",
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val expiresAt: OffsetDateTime? = null
)
