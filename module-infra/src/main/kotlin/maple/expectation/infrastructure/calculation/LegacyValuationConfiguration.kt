package maple.expectation.infrastructure.calculation

import maple.expectation.core.calculation.ValuationKernel
import maple.expectation.core.calculation.cube.CubeTrialsKernel
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.policy.TableBasedCostStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class LegacyValuationConfiguration {

    @Bean
    fun legacyProbabilityTableLoader(): LegacyProbabilityTableLoader = LegacyProbabilityTableLoader()

    @Bean
    fun legacyProbabilityTableSnapshot(loader: LegacyProbabilityTableLoader): ProbabilityTableSnapshot =
        loader.load()

    @Bean
    fun legacyValuationKernel(): ValuationKernel =
        ValuationKernel(TableBasedCostStrategy(), CubeTrialsKernel())
}
