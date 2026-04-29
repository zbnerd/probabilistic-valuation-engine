package maple.expectation.application.adapter

import maple.expectation.application.service.expectation.PureExpectationCalculator
import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4
import maple.expectation.core.port.out.PureCalculationPort
import org.springframework.stereotype.Component

@Component
class PureCalculationAdapter(
    private val calculator: PureExpectationCalculator,
) : PureCalculationPort {
    override fun calculate(input: CalculationInput): EquipmentExpectationResponseV4 = calculator.calculate(input)
}
