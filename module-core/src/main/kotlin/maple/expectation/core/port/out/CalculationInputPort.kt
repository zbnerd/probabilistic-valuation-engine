package maple.expectation.core.port.out

import java.util.UUID
import maple.expectation.core.dto.v4.CalculationInput

interface CalculationInputPort {
    fun save(input: CalculationInput): CalculationInput
    fun findByJobId(jobId: UUID): CalculationInput?
    fun saveIfAbsent(input: CalculationInput): Boolean
}
