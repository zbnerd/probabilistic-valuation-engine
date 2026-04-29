package maple.expectation.infrastructure.persistence.repository

import java.time.Instant
import java.util.UUID
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import org.springframework.data.jpa.repository.JpaRepository

interface CalculationSnapshotRepository : JpaRepository<CalculationSnapshotEntity, UUID> {

    fun findByJobId(jobId: UUID): CalculationSnapshotEntity?

    fun findByExpiresAtBefore(cutoff: Instant): List<CalculationSnapshotEntity>
}
