package maple.expectation.application.service.calculator.v4

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.system.measureNanoTime
import maple.expectation.core.calculation.ValuationKernel
import maple.expectation.core.dto.cube.CubeCalculationInput
import maple.expectation.core.dto.v4.EquipmentCalculationInput
import maple.expectation.core.policy.TableBasedCostStrategy
import maple.expectation.infrastructure.calculation.LegacyProbabilityTableLoader
import maple.expectation.infrastructure.persistence.repository.CubeProbabilityRepositoryImpl
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LegacyValuationGoldenMasterTest {

    private val mapper = jacksonObjectMapper()
    private lateinit var engine: LegacyEngine
    private lateinit var fixtures: List<GoldenCase>
    private lateinit var fixtureBytes: ByteArray

    @BeforeAll
    fun loadFrozenInputsAndLegacyEngine() {
        fixtureBytes = requireNotNull(javaClass.classLoader.getResourceAsStream(FIXTURE_RESOURCE)) {
            "Missing golden fixture: $FIXTURE_RESOURCE"
        }.use { it.readBytes() }
        fixtures = mapper.readValue(fixtureBytes, object : TypeReference<List<GoldenCase>>() {})
        engine = legacyEngine()
    }

    @Test
    fun frozenLegacyOutputsRemainExact() {
        assertFixtureMatrix()
        fixtures.forEach(::assertFrozenCase)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALUATION_EVIDENCE_ENABLED", matches = "1")
    fun recordLegacyThroughputAndAllocationEvidence() {
        val validFixtures = fixtures.filter { it.expected != null }
        val threadBean = allocationBean()
        val repetitions = (1..REPETITION_COUNT).map { repetition ->
            repeat(WARMUP_PASSES) { validFixtures.forEach(::evaluateSuccessfully) }

            val threadId = Thread.currentThread().id
            val allocationBefore = threadBean.getThreadAllocatedBytes(threadId)
            val startedAt = System.nanoTime()
            repeat(MEASURED_PASSES) { validFixtures.forEach(::evaluateSuccessfully) }
            val elapsedNanos = System.nanoTime() - startedAt
            val allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocationBefore
            val itemCount = validFixtures.size * MEASURED_PASSES
            LegacyEvidenceRepetition(
                repetition = repetition,
                items = itemCount,
                elapsedNanos = elapsedNanos,
                itemsPerSecond = itemCount * NANOS_PER_SECOND / elapsedNanos,
                allocatedBytes = allocatedBytes,
                allocatedBytesPerItem = allocatedBytes.toDouble() / itemCount,
            )
        }
        val report = LegacyEvidence(
            fixtureSha256 = sha256(fixtureBytes),
            fixtureCount = fixtures.size,
            tableLogicalVersion = engine.repository.getCurrentTableVersion(),
            tableRows = engine.rowCount,
            tableLoadDurationNanos = engine.loadDurationNanos,
            warmupPassesPerRepetition = WARMUP_PASSES,
            measuredPassesPerRepetition = MEASURED_PASSES,
            jdk = System.getProperty("java.runtime.version"),
            cpu = System.getProperty("os.arch"),
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            jvmFlags = ManagementFactory.getRuntimeMXBean().inputArguments.toList(),
            repetitions = repetitions,
            medianItemsPerSecond = repetitions.map { it.itemsPerSecond }.sorted()[repetitions.size / 2],
            medianAllocatedBytesPerItem = repetitions.map { it.allocatedBytesPerItem }.sorted()[repetitions.size / 2],
        )
        val output = Path.of("build/reports/valuation-evidence/legacy.json")
        Files.createDirectories(output.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report)
    }

    private fun assertFixtureMatrix() {
        assertThat(fixtures).hasSize(26)
        assertThat(fixtures.map { it.id }).contains(
            "full-no-enhancements",
            "full-no-enhancements-level-0",
            "full-potential-rare-level-100",
            "full-potential-epic-level-100",
            "full-potential-unique-level-150",
            "full-potential-legendary-level-200",
            "full-potential-legendary-level-250",
            "full-additional-only-level-100",
            "full-starforce-only-normal-range",
            "full-all-components-decorator-order",
            "black-component-entry",
            "additional-component-entry",
            "starforce-component-entry",
            "full-current-star-equals-target",
            "full-regular-maximum-star",
            "full-noljang-zero-boundary",
            "full-noljang-eleven-boundary",
            "full-noljang-twelve-boundary",
            "full-noljang-requested-20-capped-15-regular-cost",
            "full-secondary-weapon-standard-normalization",
            "full-secondary-weapon-force-shield-normalization",
            "full-option-order-original",
            "full-option-order-reordered",
            "full-all-stat-contribution",
            "full-compound-option-permutation-fallback",
            "unsupported-potential-grade",
        )
        fixtures.forEach { fixture ->
            require((fixture.expected != null) xor (fixture.expectedErrorType != null)) {
                "${fixture.id} must define exactly one of expected or expectedErrorType"
            }
        }
    }

    private fun assertFrozenCase(fixture: GoldenCase) {
        val outcome = runCatching { evaluate(fixture) }
        fixture.expectedErrorType?.let { expectedType ->
            val failure = requireNotNull(outcome.exceptionOrNull()) {
                "${fixture.id} unexpectedly succeeded"
            }
            assertThat(failure.javaClass.name).describedAs(fixture.id).isEqualTo(expectedType)
            return
        }

        val expected = requireNotNull(fixture.expected) { "${fixture.id} is missing expected output" }
        val actual = outcome.getOrThrow()
        assertThat(actual.blackCubeCost).describedAs("${fixture.id}.blackCubeCost").isEqualTo(expected.blackCubeCost)
        assertThat(actual.redCubeCost).describedAs("${fixture.id}.redCubeCost").isEqualTo(expected.redCubeCost)
        assertThat(actual.additionalCubeCost).describedAs("${fixture.id}.additionalCubeCost").isEqualTo(expected.additionalCubeCost)
        assertThat(actual.starforceCost).describedAs("${fixture.id}.starforceCost").isEqualTo(expected.starforceCost)
        assertTrial(fixture.id, "blackCubeTrials", actual.blackCubeTrials, expected.blackCubeTrials)
        assertTrial(fixture.id, "redCubeTrials", actual.redCubeTrials, expected.redCubeTrials)
        assertTrial(fixture.id, "additionalCubeTrials", actual.additionalCubeTrials, expected.additionalCubeTrials)
        assertThat(actual.totalCost).describedAs("${fixture.id}.totalCost").isEqualTo(expected.totalCost)
        assertThat(actual.enhancePath).describedAs("${fixture.id}.enhancePath").isEqualTo(expected.enhancePath)
        assertThat(actual.tableLogicalVersion).describedAs("${fixture.id}.tableLogicalVersion").isEqualTo(expected.tableLogicalVersion)
    }

    private fun assertTrial(caseId: String, field: String, actual: Double, expected: Double) {
        if (expected % 1.0 == 0.0) {
            assertThat(actual).describedAs("$caseId.$field").isEqualTo(expected)
        } else {
            assertThat(actual).describedAs("$caseId.$field").isCloseTo(expected, offset(NON_INTEGRAL_TRIAL_TOLERANCE))
        }
    }

    private fun evaluateSuccessfully(fixture: GoldenCase) {
        evaluate(fixture)
    }

    private fun evaluate(fixture: GoldenCase): ExpectedResult {
        val calculator = calculatorFor(fixture, engine.factory)
        val details = calculator.detailedCosts
        return ExpectedResult(
            blackCubeCost = details.blackCubeCost,
            redCubeCost = details.redCubeCost,
            additionalCubeCost = details.additionalCubeCost,
            starforceCost = details.starforceCost,
            blackCubeTrials = details.blackCubeTrials,
            redCubeTrials = details.redCubeTrials,
            additionalCubeTrials = details.additionalCubeTrials,
            totalCost = calculator.calculateCost(),
            enhancePath = calculator.enhancePath,
            tableLogicalVersion = engine.repository.getCurrentTableVersion(),
        )
    }

    private fun calculatorFor(
        fixture: GoldenCase,
        factory: EquipmentExpectationCalculatorFactory,
    ): EquipmentExpectationCalculator = when (fixture.entryPoint) {
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

    private fun legacyEngine(): LegacyEngine {
        lateinit var repository: CubeProbabilityRepositoryImpl
        lateinit var factory: EquipmentExpectationCalculatorFactory
        val loadDurationNanos = measureNanoTime {
            val snapshot = LegacyProbabilityTableLoader().load()
            repository = CubeProbabilityRepositoryImpl(snapshot).also { it.init() }
            factory = EquipmentExpectationCalculatorFactory(
                ValuationKernel(TableBasedCostStrategy()),
                snapshot,
            )
        }
        return LegacyEngine(
            factory = factory,
            repository = repository,
            loadDurationNanos = loadDurationNanos,
            rowCount = repository.findAll().size,
        )
    }

    private fun allocationBean(): ThreadMXBean {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
            ?: error("Thread allocation measurement is unavailable")
        require(bean.isThreadAllocatedMemorySupported) { "Thread allocation measurement is unsupported" }
        if (!bean.isThreadAllocatedMemoryEnabled) {
            bean.isThreadAllocatedMemoryEnabled = true
        }
        return bean
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class LegacyEngine(
        val factory: EquipmentExpectationCalculatorFactory,
        val repository: CubeProbabilityRepositoryImpl,
        val loadDurationNanos: Long,
        val rowCount: Int,
    )

    private enum class EntryPoint { FULL, BLACK, ADDITIONAL, STARFORCE }

    private data class GoldenCase(
        val id: String,
        val entryPoint: EntryPoint,
        val sourceTargetStar: Int?,
        val input: CanonicalInput,
        val expected: ExpectedResult?,
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
    }

    private data class ExpectedResult(
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

    private data class LegacyEvidence(
        val fixtureSha256: String,
        val fixtureCount: Int,
        val tableLogicalVersion: String,
        val tableRows: Int,
        val tableLoadDurationNanos: Long,
        val warmupPassesPerRepetition: Int,
        val measuredPassesPerRepetition: Int,
        val jdk: String,
        val cpu: String,
        val availableProcessors: Int,
        val jvmFlags: List<String>,
        val repetitions: List<LegacyEvidenceRepetition>,
        val medianItemsPerSecond: Double,
        val medianAllocatedBytesPerItem: Double,
    )

    private data class LegacyEvidenceRepetition(
        val repetition: Int,
        val items: Int,
        val elapsedNanos: Long,
        val itemsPerSecond: Double,
        val allocatedBytes: Long,
        val allocatedBytesPerItem: Double,
    )

    private companion object {
        const val FIXTURE_RESOURCE = "golden/valuation-kernel-v1-cases.json"
        const val WARMUP_PASSES = 25
        const val MEASURED_PASSES = 250
        const val REPETITION_COUNT = 5
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val NON_INTEGRAL_TRIAL_TOLERANCE = 1e-12
    }
}
