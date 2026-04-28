package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotInputEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CalculationSnapshotInputRepository : JpaRepository<CalculationSnapshotInputEntity, UUID> {
    fun findByJobId(jobId: UUID): CalculationSnapshotInputEntity?
}
