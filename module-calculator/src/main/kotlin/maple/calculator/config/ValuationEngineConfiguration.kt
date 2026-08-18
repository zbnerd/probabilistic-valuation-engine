package maple.calculator.config

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit
import maple.calculator.probability.CsvProbabilityTableLoader
import maple.expectation.core.calculation.ValuationKernel
import maple.expectation.core.calculation.cube.CubeTrialsKernel
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.policy.CostCalculationStrategy
import maple.expectation.core.policy.TableBasedCostStrategy
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ValuationEngineConfiguration {

    @Bean
    fun csvProbabilityTableLoader(): CsvProbabilityTableLoader = CsvProbabilityTableLoader()

    @Bean
    fun probabilityTableSnapshot(
        loader: CsvProbabilityTableLoader,
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): ProbabilityTableSnapshot {
        val snapshot = loader.load()
        loader.lastObservation?.let { observation ->
            meterRegistry.getIfAvailable()?.let { registry ->
                registry.timer(
                    TABLE_LOAD_TIMER,
                    VERSION_TAG,
                    observation.versionLabel,
                ).record(observation.durationNanos, TimeUnit.NANOSECONDS)
                Gauge.builder(TABLE_ROWS_GAUGE, observation) { value -> value.rowCount.toDouble() }
                    .tag(VERSION_TAG, observation.versionLabel)
                    .strongReference(true)
                    .register(registry)
            }
        }
        return snapshot
    }

    @Bean
    fun costCalculationStrategy(): CostCalculationStrategy = TableBasedCostStrategy()

    @Bean
    fun cubeTrialsKernel(): CubeTrialsKernel = CubeTrialsKernel()

    @Bean
    fun valuationKernel(
        costStrategy: CostCalculationStrategy,
        cubeTrialsKernel: CubeTrialsKernel,
    ): ValuationKernel = ValuationKernel(costStrategy, cubeTrialsKernel)

    private companion object {
        const val TABLE_LOAD_TIMER = "valuation.probability.table.load"
        const val TABLE_ROWS_GAUGE = "valuation.probability.table.rows"
        const val VERSION_TAG = "version"
    }
}
