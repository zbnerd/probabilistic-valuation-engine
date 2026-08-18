package maple.calculator.processor

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.system.measureNanoTime
import maple.calculator.cache.CacheConfig
import maple.calculator.cache.CaffeineCacheBackend
import maple.calculator.metrics.ValuationCacheMetrics
import maple.calculator.probability.CsvProbabilityTableLoader
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.expectation.core.calculation.ValuationInput
import maple.expectation.core.calculation.ValuationKernel
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.policy.TableBasedCostStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

class ValuationPerformanceEvidenceTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "VALUATION_EVIDENCE_ENABLED", matches = "1")
    fun `record direct kernel throughput and allocation evidence`() {
        val root = repositoryRoot()
        val fixturePath = root.resolve(FIXTURE_PATH)
        val fixtureBytes = Files.readAllBytes(fixturePath)
        assertThat(sha256(fixtureBytes)).isEqualTo(FIXTURE_SHA)

        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val fixtures: List<GoldenCase> = mapper.readValue(
            fixtureBytes,
            object : TypeReference<List<GoldenCase>>() {},
        )
        val validFixtures = fixtures.filter { fixture -> fixture.expectedErrorType == null }

        val loader = CsvProbabilityTableLoader()
        lateinit var table: ProbabilityTableSnapshot
        val tableLoadDurationNanos = measureNanoTime { table = loader.load() }
        assertThat(table.rowCount).isEqualTo(TABLE_ROWS)
        val kernel = ValuationKernel(TableBasedCostStrategy())
        val threadBean = allocationBean()

        val repetitions = (1..REPETITION_COUNT).map { repetition ->
            repeat(WARMUP_PASSES) { validFixtures.forEach { fixture -> evaluate(kernel, table, fixture) } }

            val threadId = Thread.currentThread().id
            val allocationBefore = threadBean.getThreadAllocatedBytes(threadId)
            val startedAt = System.nanoTime()
            repeat(MEASURED_PASSES) { validFixtures.forEach { fixture -> evaluate(kernel, table, fixture) } }
            val elapsedNanos = System.nanoTime() - startedAt
            val allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocationBefore
            val itemCount = validFixtures.size * MEASURED_PASSES
            KernelEvidenceRepetition(
                repetition = repetition,
                items = itemCount,
                elapsedNanos = elapsedNanos,
                itemsPerSecond = itemCount * NANOS_PER_SECOND / elapsedNanos,
                allocatedBytes = allocatedBytes,
                allocatedBytesPerItem = allocatedBytes.toDouble() / itemCount,
            )
        }

        val cacheBackend = CaffeineCacheBackend<ValuationCacheKey, maple.expectation.core.calculation.ValuationResult>(
            CacheConfig(maxEntries = validFixtures.size.toLong() + 1L),
        )
        val valuationCache = ValuationCache(
            kernel,
            table,
            cacheBackend,
            ValuationCacheMetrics(SimpleMeterRegistry()),
        )
        validFixtures.forEach { fixture -> evaluate(valuationCache, fixture) }
        val missesAfterPreload = cacheBackend.stats().misses

        val cacheHitRepetitions = (1..REPETITION_COUNT).map { repetition ->
            repeat(WARMUP_PASSES) { validFixtures.forEach { fixture -> evaluate(valuationCache, fixture) } }

            val threadId = Thread.currentThread().id
            val allocationBefore = threadBean.getThreadAllocatedBytes(threadId)
            val startedAt = System.nanoTime()
            repeat(MEASURED_PASSES) { validFixtures.forEach { fixture -> evaluate(valuationCache, fixture) } }
            val elapsedNanos = System.nanoTime() - startedAt
            val allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocationBefore
            val itemCount = validFixtures.size * MEASURED_PASSES
            KernelEvidenceRepetition(
                repetition = repetition,
                items = itemCount,
                elapsedNanos = elapsedNanos,
                itemsPerSecond = itemCount * NANOS_PER_SECOND / elapsedNanos,
                allocatedBytes = allocatedBytes,
                allocatedBytesPerItem = allocatedBytes.toDouble() / itemCount,
            )
        }
        val cacheMissesDuringHitPhase = cacheBackend.stats().misses - missesAfterPreload
        assertThat(cacheMissesDuringHitPhase).isZero()

        val report = KernelEvidence(
            fixtureSha256 = FIXTURE_SHA,
            fixtureCount = fixtures.size,
            tableLogicalVersion = table.version.logical,
            tableRows = table.rowCount,
            tableLoadDurationNanos = tableLoadDurationNanos,
            warmupPassesPerRepetition = WARMUP_PASSES,
            measuredPassesPerRepetition = MEASURED_PASSES,
            jdk = System.getProperty("java.runtime.version"),
            cpu = System.getProperty("os.arch"),
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            jvmFlags = ManagementFactory.getRuntimeMXBean().inputArguments.toList(),
            repetitions = repetitions,
            medianItemsPerSecond = repetitions.map { it.itemsPerSecond }.sorted()[repetitions.size / 2],
            medianAllocatedBytesPerItem = repetitions.map { it.allocatedBytesPerItem }.sorted()[repetitions.size / 2],
            cacheHitRepetitions = cacheHitRepetitions,
            medianCacheHitItemsPerSecond = cacheHitRepetitions
                .map { it.itemsPerSecond }
                .sorted()[cacheHitRepetitions.size / 2],
            medianCacheHitAllocatedBytesPerItem = cacheHitRepetitions
                .map { it.allocatedBytesPerItem }
                .sorted()[cacheHitRepetitions.size / 2],
            cacheMissesDuringHitPhase = cacheMissesDuringHitPhase,
        )
        val output = root.resolve("module-calculator/build/reports/valuation-evidence/kernel.json")
        Files.createDirectories(output.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report)
        cacheBackend.close()
    }

    private fun evaluate(
        kernel: ValuationKernel,
        table: ProbabilityTableSnapshot,
        fixture: GoldenCase,
    ) {
        kernel.calculate(fixture.input.toValuationInput(), table)
    }

    private fun evaluate(cache: ValuationCache, fixture: GoldenCase) {
        cache.getOrCalculate(fixture.input.toValuationInput())
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

    private fun repositoryRoot(): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        var cursor: Path? = start
        repeat(5) {
            val candidate = cursor
            if (candidate != null && Files.isDirectory(candidate.resolve("module-calculator"))) {
                return candidate
            }
            cursor = candidate?.parent
        }
        throw IllegalStateException("Could not locate repository root from $start")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class GoldenCase(
        val id: String,
        val input: CanonicalInput,
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
        fun toValuationInput(): ValuationInput = ValuationInput(
            itemName = itemName,
            part = part,
            equipmentPart = equipmentPart,
            itemLevel = itemLevel,
            currentStar = currentStar,
            targetStar = targetStar,
            noljang = noljang,
            potentialGrade = potentialGrade,
            potentialOptions = potentialOptions.toList(),
            additionalGrade = additionalGrade,
            additionalOptions = additionalOptions.toList(),
        )
    }

    private data class KernelEvidence(
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
        val repetitions: List<KernelEvidenceRepetition>,
        val medianItemsPerSecond: Double,
        val medianAllocatedBytesPerItem: Double,
        val cacheHitRepetitions: List<KernelEvidenceRepetition>,
        val medianCacheHitItemsPerSecond: Double,
        val medianCacheHitAllocatedBytesPerItem: Double,
        val cacheMissesDuringHitPhase: Long,
    )

    private data class KernelEvidenceRepetition(
        val repetition: Int,
        val items: Int,
        val elapsedNanos: Long,
        val itemsPerSecond: Double,
        val allocatedBytes: Long,
        val allocatedBytesPerItem: Double,
    )

    private companion object {
        const val FIXTURE_PATH = "module-infra/src/test/resources/golden/valuation-kernel-v1-cases.json"
        const val FIXTURE_SHA = "4eb178a35b04a29157a3464f3f139124f80f6c9fb02bb14a21ecd5ee21ccc8c2"
        const val TABLE_ROWS = 413_802
        const val WARMUP_PASSES = 25
        const val MEASURED_PASSES = 250
        const val REPETITION_COUNT = 5
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
