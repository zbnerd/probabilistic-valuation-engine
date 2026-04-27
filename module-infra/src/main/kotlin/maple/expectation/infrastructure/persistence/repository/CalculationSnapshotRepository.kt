package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface CalculationSnapshotRepository : JpaRepository<CalculationSnapshotEntity, UUID> {

    fun findByJobId(jobId: UUID): CalculationSnapshotEntity?

    fun findByExpiresAtBefore(cutoff: Instant): List<CalculationSnapshotEntity>
}
