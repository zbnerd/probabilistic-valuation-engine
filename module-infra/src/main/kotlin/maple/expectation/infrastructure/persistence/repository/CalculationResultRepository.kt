package maple.expectation.infrastructure.persistence.repository

import java.util.UUID
import maple.expectation.infrastructure.persistence.entity.CalculationResultEntity
import org.springframework.data.jpa.repository.JpaRepository

interface CalculationResultRepository : JpaRepository<CalculationResultEntity, UUID> {
    fun findByJobId(jobId: UUID): CalculationResultEntity?
    fun existsByJobId(jobId: UUID): Boolean
}
