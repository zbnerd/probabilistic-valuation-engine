package maple.expectation.infrastructure.persistence.repository

import java.util.UUID
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotInputEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CalculationSnapshotInputRepository : JpaRepository<CalculationSnapshotInputEntity, UUID> {
    fun findByJobId(jobId: UUID): CalculationSnapshotInputEntity?

    @Modifying
    @Query(
        value = """
            INSERT INTO calculation_snapshot_inputs (input_id, job_id, schema_version, payload, created_at)
            VALUES (:inputId, :jobId, :schemaVersion, CAST(:payload AS jsonb), now())
            ON CONFLICT (job_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("inputId") inputId: UUID,
        @Param("jobId") jobId: UUID,
        @Param("schemaVersion") schemaVersion: Int,
        @Param("payload") payload: String,
    ): Int
}
