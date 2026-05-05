package maple.expectation.infrastructure.config

import maple.expectation.core.calculator.CubeRateCalculator
import maple.expectation.core.domain.stat.StatParser
import maple.expectation.core.flame.component.FlameScoreResolver
import maple.expectation.core.flame.port.FlameTrialsPort
import maple.expectation.core.flame.service.FlameTrialsService
import maple.expectation.core.probability.FlameDpCalculator
import maple.expectation.core.probability.FlameScoreCalculator
import maple.expectation.core.probability.ProbabilityConvolver
import maple.expectation.core.probability.TailProbabilityCalculator
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CalculationPortConfig {

    private val log = LoggerFactory.getLogger(CalculationPortConfig::class.java)

    @Bean
    fun flameTrialsPort(
        dpCalculator: FlameDpCalculator,
        scoreCalculator: FlameScoreCalculator,
    ): FlameTrialsPort {
        log.info("[CalculationPort] Initializing FlameTrialsPort bean")
        return FlameTrialsService(dpCalculator, scoreCalculator)
    }

    @Bean
    fun flameDpCalculator(): FlameDpCalculator {
        return FlameDpCalculator()
    }

    @Bean
    fun flameScoreCalculator(): FlameScoreCalculator {
        return FlameScoreCalculator()
    }

    @Bean
    fun cubeRateCalculator(): CubeRateCalculator {
        return CubeRateCalculator()
    }

    @Bean
    fun statParser(): StatParser {
        return StatParser()
    }

    @Bean
    fun probabilityConvolver(): ProbabilityConvolver {
        return ProbabilityConvolver()
    }

    @Bean
    fun tailProbabilityCalculator(): TailProbabilityCalculator {
        return TailProbabilityCalculator()
    }

    @Bean
    fun flameScoreResolver(): FlameScoreResolver {
        return FlameScoreResolver
    }
}
