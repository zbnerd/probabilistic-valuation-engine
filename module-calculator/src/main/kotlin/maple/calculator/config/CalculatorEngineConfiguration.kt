package maple.calculator.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@Import(ValuationEngineConfiguration::class)
class CalculatorEngineConfiguration
