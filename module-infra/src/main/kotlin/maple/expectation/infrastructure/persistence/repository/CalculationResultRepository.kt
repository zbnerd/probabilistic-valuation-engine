package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.CalculationResultEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CalculationResultRepository : JpaRepository<CalculationResultEntity, UUID> {
    fun findByJobId(jobId: UUID): CalculationResultEntity?
    fun existsByJobId(jobId: UUID): Boolean
}
