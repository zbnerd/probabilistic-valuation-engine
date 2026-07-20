package maple.calculator.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.calculator.probability.CsvProbabilityTableLoader
import maple.expectation.core.calculation.ValuationKernel
import maple.expectation.core.calculation.cube.CubeTrialsKernel
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.policy.CostCalculationStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ValuationEngineConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
        .withUserConfiguration(CalculatorEngineConfiguration::class.java)

    @Test
    fun `local calculator configuration owns one complete valuation engine`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(CsvProbabilityTableLoader::class.java)
            assertThat(context).hasSingleBean(ProbabilityTableSnapshot::class.java)
            assertThat(context).hasSingleBean(CostCalculationStrategy::class.java)
            assertThat(context).hasSingleBean(CubeTrialsKernel::class.java)
            assertThat(context).hasSingleBean(ValuationKernel::class.java)
            assertThat(context).doesNotHaveBean("calculatorEngineAutoConfiguration")
            assertThat(context).doesNotHaveBean("coreExecutorConfig")

            val snapshot = context.getBean(ProbabilityTableSnapshot::class.java)
            assertThat(snapshot.rowCount).isEqualTo(413_802)
            assertThat(snapshot.version.logical).isEqualTo("csv-v1.0")
            assertThat(snapshot.version.contentSha256).isEqualTo(BASELINE_SHA)

            val registry = context.getBean(MeterRegistry::class.java)
            assertThat(registry.get("valuation.probability.table.load").tag("version", "csv-v1.0").timer().count())
                .isEqualTo(1L)
            assertThat(registry.get("valuation.probability.table.rows").tag("version", "csv-v1.0").gauge().value())
                .isEqualTo(413_802.0)
        }
    }

    private companion object {
        const val BASELINE_SHA = "9a329fe4b861c9f21b69d766e1847e59982c2a02ea4f30c6e5b332a7f2e955c0"
    }
}
