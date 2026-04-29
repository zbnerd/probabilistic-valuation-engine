package maple.expectation.infrastructure.persistence.repository

import java.util.UUID
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotInputEntity
import org.springframework.data.jpa.repository.JpaRepository

interface CalculationSnapshotInputRepository : JpaRepository<CalculationSnapshotInputEntity, UUID> {
    fun findByJobId(jobId: UUID): CalculationSnapshotInputEntity?
}
