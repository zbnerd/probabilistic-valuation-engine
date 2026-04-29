package maple.expectation.core.port.out

import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4

interface PureCalculationPort {
    fun calculate(input: CalculationInput): EquipmentExpectationResponseV4
}
