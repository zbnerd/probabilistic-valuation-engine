package maple.calculator.config

import maple.expectation.infrastructure.config.CalculatorEngineAutoConfiguration
import maple.expectation.infrastructure.config.CoreExecutorConfig
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Calculator Engine Configuration — module-calculator의 2-import facade.
 *
 * <p>Cube engine의 17개 빈은 {@link CalculatorEngineAutoConfiguration}이 소유.
 * 이 클래스는 {@link CoreExecutorConfig}를 함께 import하여 lightweight executor
 * 빈이 calculator에 노출되도록 한다.
 *
 * @see maple.expectation.infrastructure.config.CalculatorEngineAutoConfiguration
 */
@Configuration
@Import(
    CalculatorEngineAutoConfiguration::class,
    CoreExecutorConfig::class,
)
class CalculatorEngineConfiguration
