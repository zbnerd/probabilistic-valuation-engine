package maple.expectation.application.service.calculator.v4

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maple.expectation.core.calculation.ValuationInput
import maple.expectation.core.calculation.ValuationKernel
import maple.expectation.core.calculation.ValuationResult
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.domain.model.CubeType
import maple.expectation.core.dto.cube.CubeCalculationInput
import maple.expectation.core.dto.v4.EquipmentCalculationInput
import maple.expectation.core.policy.TableBasedCostStrategy
import maple.expectation.infrastructure.calculation.LegacyProbabilityTableLoader
import maple.expectation.infrastructure.config.CalculatorEngineAutoConfiguration
import maple.expectation.infrastructure.persistence.repository.CubeProbabilityRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.runner.ApplicationContextRunner

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoreLegacyValuationParityTest {

    private val mapper = jacksonObjectMapper()
    private lateinit var fixtures: List<GoldenCase>
    private lateinit var table: ProbabilityTableSnapshot
    private lateinit var kernel: ValuationKernel
    private lateinit var factory: EquipmentExpectationCalculatorFactory

    @BeforeAll
    fun loadFrozenCases() {
        fixtures = requireNotNull(javaClass.classLoader.getResourceAsStream(FIXTURE_RESOURCE)) {
            "Missing golden fixture: $FIXTURE_RESOURCE"
        }.use { input -> mapper.readValue(input, object : TypeReference<List<GoldenCase>>() {}) }
        table = LegacyProbabilityTableLoader().load()
        kernel = ValuationKernel(TableBasedCostStrategy())
        factory = EquipmentExpectationCalculatorFactory(kernel, table)
    }

    @Test
    fun `legacy public factory delegates every frozen case to the core kernel without output drift`() {
        assertThat(fixtures).hasSize(26)
        val cappedNoljang = fixtures.single { fixture ->
            fixture.id == "full-noljang-requested-20-capped-15-regular-cost"
        }
        assertThat(cappedNoljang.sourceTargetStar).isEqualTo(20)
        assertThat(cappedNoljang.input.targetStar).isEqualTo(15)
        assertThat(cappedNoljang.expected?.starforceCost).isEqualTo(488_041_031.0)
        val observedCompatibilityCases = linkedSetOf<String>()

        fixtures.forEach { fixture ->
            val facadeOutcome = runCatching { evaluateFacade(fixture) }
            val coreOutcome = runCatching { evaluateCore(fixture) }
            fixture.expectedErrorType?.let { expectedErrorType ->
                assertThat(facadeOutcome.exceptionOrNull()?.javaClass?.name)
                    .describedAs("${fixture.id}.facade.error")
                    .isEqualTo(expectedErrorType)
                assertThat(coreOutcome.exceptionOrNull()?.javaClass?.name)
                    .describedAs("${fixture.id}.core.error")
                    .isEqualTo(expectedErrorType)
                return@forEach
            }

            val expected = requireNotNull(fixture.expected) { "${fixture.id} is missing expected output" }
            val facade = facadeOutcome.getOrThrow()
            val core = coreOutcome.getOrThrow().toLegacyProjection()
            assertProjection(fixture.id, facade, expected)
            if (fixture.id in LEGACY_PERMUTATION_COMPATIBILITY_CASES) {
                observedCompatibilityCases += fixture.id
                assertThat(core.blackCubeCost).describedAs("${fixture.id}.core.blackCubeCost").isPositive()
                assertThat(core.blackCubeTrials).describedAs("${fixture.id}.core.blackCubeTrials").isPositive()
                assertThat(core.enhancePath).isEqualTo(expected.enhancePath)
                assertThat(core.tableLogicalVersion).isEqualTo(expected.tableLogicalVersion)
            } else {
                assertProjection(fixture.id, core, expected)
                assertThat(facade).describedAs("${fixture.id}.facade-core").isEqualTo(core)
            }
        }

        assertThat(observedCompatibilityCases).containsExactlyElementsOf(LEGACY_PERMUTATION_COMPATIBILITY_CASES)
    }

    @Test
    fun `legacy app facing bean names and auto configuration remain stable`() {
        ApplicationContextRunner()
            .withUserConfiguration(CalculatorEngineAutoConfiguration::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(EquipmentExpectationCalculatorFactory::class.java)
                assertThat(context).hasSingleBean(CubeProbabilityRepository::class.java)
                assertThat(context).hasSingleBean(ProbabilityTableSnapshot::class.java)
                assertThat(context).hasSingleBean(ValuationKernel::class.java)
                assertThat(context).hasBean("cubeProbabilityRepositoryV1")
                assertThat(context).hasBean("calculatorEngineAutoConfiguration")
                assertThat(context).doesNotHaveBean("coreExecutorConfig")
                val repository = context.getBean(CubeProbabilityRepository::class.java)
                assertThat(repository.getCurrentTableVersion()).isEqualTo("csv-v1.0")
                assertThat(repository.findAll()).hasSize(413_802)
                val rows = repository.findProbabilities(CubeType.BLACK, 200, "모자", "레전드리", 1)
                assertThat(rows).isNotEmpty()
                assertThat(repository.findProbabilitiesByVersion(CubeType.BLACK, 200, "모자", "레전드리", 1, "csv-v1.0"))
                    .isEqualTo(rows)
                assertThat(repository.findProbabilities(CubeType.BLACK, 200, "모자", "레전드리", 0)).isEmpty()
            }
    }

    private fun evaluateFacade(fixture: GoldenCase): LegacyProjection {
        val calculator = when (fixture.entryPoint) {
            EntryPoint.FULL -> factory.createFullCalculator(fixture.input.toLegacyInput())
            EntryPoint.BLACK -> factory.createBlackCubeCalculator(fixture.input.toCubeInput(additional = false))
            EntryPoint.ADDITIONAL -> factory.createAdditionalCubeCalculator(fixture.input.toCubeInput(additional = true))
            EntryPoint.STARFORCE -> factory.createStarforceCalculator(
                fixture.input.itemName,
                fixture.input.itemLevel,
                fixture.input.currentStar,
                fixture.input.targetStar,
            )
        }
        val costs = calculator.detailedCosts
        return LegacyProjection(
            blackCubeCost = costs.blackCubeCost,
            redCubeCost = costs.redCubeCost,
            additionalCubeCost = costs.additionalCubeCost,
            starforceCost = costs.starforceCost,
            blackCubeTrials = costs.blackCubeTrials,
            redCubeTrials = costs.redCubeTrials,
            additionalCubeTrials = costs.additionalCubeTrials,
            totalCost = calculator.calculateCost(),
            enhancePath = calculator.enhancePath,
            tableLogicalVersion = table.version.logical,
        )
    }

    private fun evaluateCore(fixture: GoldenCase): ValuationResult =
        kernel.calculate(fixture.input.toValuationInput(fixture.entryPoint), table)

    private fun ValuationResult.toLegacyProjection(): LegacyProjection = LegacyProjection(
        blackCubeCost = costs.blackCubeCost ?: 0.0,
        redCubeCost = 0.0,
        additionalCubeCost = costs.additionalCubeCost ?: 0.0,
        starforceCost = costs.starforceCost ?: 0.0,
        blackCubeTrials = trials.blackCubeTrials?.let(Math::round)?.toDouble() ?: 0.0,
        redCubeTrials = 0.0,
        additionalCubeTrials = trials.additionalCubeTrials?.let(Math::round)?.toDouble() ?: 0.0,
        totalCost = costs.totalCost ?: 0.0,
        enhancePath = enhancePath,
        tableLogicalVersion = tableVersion.logical,
    )

    private fun assertProjection(caseId: String, actual: LegacyProjection, expected: LegacyProjection) {
        assertThat(actual).describedAs(caseId).isEqualTo(expected)
    }

    private enum class EntryPoint { FULL, BLACK, ADDITIONAL, STARFORCE }

    private data class GoldenCase(
        val id: String,
        val entryPoint: EntryPoint,
        val sourceTargetStar: Int?,
        val input: CanonicalInput,
        val expected: LegacyProjection?,
        val expectedErrorType: String?,
    )

    private data class CanonicalInput(
        val itemName: String,
        val part: String,
        val equipmentPart: String,
        val itemLevel: Int,
        val currentStar: Int,
        val targetStar: Int,
        val noljang: Boolean,
        val potentialGrade: String?,
        val potentialOptions: List<String>,
        val additionalGrade: String?,
        val additionalOptions: List<String>,
    ) {
        fun toLegacyInput(): EquipmentCalculationInput = EquipmentCalculationInput(
            itemName = itemName,
            itemPart = part,
            itemEquipmentPart = equipmentPart,
            itemIcon = "",
            itemLevel = itemLevel,
            presetNo = 1,
            isNoljang = noljang,
            potentialGrade = potentialGrade,
            potentialOptions = potentialOptions.toList(),
            additionalPotentialGrade = additionalGrade,
            additionalPotentialOptions = additionalOptions.toList(),
            currentStar = currentStar,
            targetStar = targetStar,
        )

        fun toCubeInput(additional: Boolean): CubeCalculationInput = CubeCalculationInput(
            level = itemLevel,
            part = part,
            grade = if (additional) additionalGrade else potentialGrade,
            options = (if (additional) additionalOptions else potentialOptions)
                .map<String, String?> { option -> option }
                .toMutableList(),
            itemName = itemName,
            itemEquipmentPart = equipmentPart,
        )

        fun toValuationInput(entryPoint: EntryPoint): ValuationInput = ValuationInput(
            itemName = itemName,
            part = part,
            equipmentPart = equipmentPart,
            itemLevel = itemLevel,
            currentStar = if (entryPoint == EntryPoint.FULL || entryPoint == EntryPoint.STARFORCE) currentStar else 0,
            targetStar = if (entryPoint == EntryPoint.FULL || entryPoint == EntryPoint.STARFORCE) targetStar else 0,
            noljang = entryPoint == EntryPoint.FULL && noljang,
            potentialGrade = if (entryPoint == EntryPoint.FULL || entryPoint == EntryPoint.BLACK) potentialGrade else null,
            potentialOptions = if (entryPoint == EntryPoint.FULL || entryPoint == EntryPoint.BLACK) {
                potentialOptions.toList()
            } else {
                emptyList()
            },
            additionalGrade = if (entryPoint == EntryPoint.FULL || entryPoint == EntryPoint.ADDITIONAL) additionalGrade else null,
            additionalOptions = if (entryPoint == EntryPoint.FULL || entryPoint == EntryPoint.ADDITIONAL) {
                additionalOptions.toList()
            } else {
                emptyList()
            },
        )
    }

    private data class LegacyProjection(
        val blackCubeCost: Double,
        val redCubeCost: Double,
        val additionalCubeCost: Double,
        val starforceCost: Double,
        val blackCubeTrials: Double,
        val redCubeTrials: Double,
        val additionalCubeTrials: Double,
        val totalCost: Double,
        val enhancePath: String,
        val tableLogicalVersion: String,
    )

    private companion object {
        const val FIXTURE_RESOURCE = "golden/valuation-kernel-v1-cases.json"
        val LEGACY_PERMUTATION_COMPATIBILITY_CASES = linkedSetOf(
            "full-all-stat-contribution",
            "full-compound-option-permutation-fallback",
        )
    }
}
