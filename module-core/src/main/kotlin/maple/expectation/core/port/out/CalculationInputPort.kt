package maple.expectation.core.port.out

import maple.expectation.core.dto.v4.CalculationInput
import java.util.UUID

interface CalculationInputPort {
    fun save(input: CalculationInput): CalculationInput
    fun findByJobId(jobId: UUID): CalculationInput?
}
