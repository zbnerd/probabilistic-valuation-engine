package maple.calculator.processor

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.atomic.AtomicLong
import maple.calculator.cache.CacheStats
import maple.calculator.cache.OffHeapCacheBackend
import maple.calculator.metrics.ValuationCacheMetrics
import maple.expectation.core.calculation.ComponentCosts
import maple.expectation.core.calculation.ComponentTrials
import maple.expectation.core.calculation.ValuationInput
import maple.expectation.core.calculation.ValuationKernel
import maple.expectation.core.calculation.ValuationResult
import maple.expectation.core.calculation.error.ValuationInvariantException
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.calculation.probability.ProbabilityTableVersion
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ValuationCacheTest {

    @Test
    fun `equal canonical identity hits while every output-affecting input field misses`() {
        val kernel = deterministicKernel()
        val backend = RecordingBackend()
        val cache = cache(kernel, table(), backend)
        val base = input()
        val changes = listOf(
            base.copy(itemName = "other-item"),
            base.copy(part = "무기"),
            base.copy(equipmentPart = "엠블렐"),
            base.copy(itemLevel = 250),
            base.copy(currentStar = 13),
            base.copy(targetStar = 23),
            base.copy(noljang = true),
            base.copy(potentialGrade = "유니크"),
            base.copy(potentialOptions = replace(base.potentialOptions, 0, "STR +13%")),
            base.copy(potentialOptions = replace(base.potentialOptions, 1, "STR +10%")),
            base.copy(potentialOptions = replace(base.potentialOptions, 2, "STR +8%")),
            base.copy(additionalGrade = "에픽"),
            base.copy(additionalOptions = replace(base.additionalOptions, 0, "STR +19")),
            base.copy(additionalOptions = replace(base.additionalOptions, 1, "STR +15")),
            base.copy(additionalOptions = replace(base.additionalOptions, 2, "STR +13")),
        )

        val first = cache.getOrCalculate(base)
        val hit = cache.getOrCalculate(base.copy())
        changes.forEach { changed -> cache.getOrCalculate(changed) }

        assertThat(hit).isSameAs(first)
        assertThat(backend.putCalls).isEqualTo(1 + changes.size)
        verify(kernel, times(1 + changes.size)).calculate(any(), any())
    }

    @Test
    fun `table logical checksum and logic versions all participate in identity`() {
        val kernel = deterministicKernel()
        val backend = RecordingBackend()
        val registry = SimpleMeterRegistry()
        val metrics = ValuationCacheMetrics(registry)
        val baseTable = table()
        val sameIdentity = table()
        val changedLogical = table(logical = "csv-v1.1")
        val changedChecksum = table(sha = SHA_B)
        val input = input()

        ValuationCache(kernel, baseTable, backend, metrics).getOrCalculate(input)
        ValuationCache(kernel, sameIdentity, backend, metrics).getOrCalculate(input.copy())
        ValuationCache(kernel, changedLogical, backend, metrics).getOrCalculate(input)
        ValuationCache(kernel, changedChecksum, backend, metrics).getOrCalculate(input)
        ValuationCache(kernel, baseTable, backend, metrics, logicVersion = "valuation-v2").getOrCalculate(input)

        assertThat(backend.putCalls).isEqualTo(4)
        verify(kernel, times(4)).calculate(any(), any())
    }

    @Test
    fun `backend get failure records one bounded metric and returns direct result`() {
        val kernel = deterministicKernel()
        val backend = RecordingBackend(getFailure = IllegalStateException("get failed"))
        val registry = SimpleMeterRegistry()
        val cache = cache(kernel, table(), backend, registry)

        val result = cache.getOrCalculate(input())

        assertThat(result.logicVersion).isEqualTo(ValuationKernel.LOGIC_VERSION)
        assertThat(failureCount(registry, "get")).isEqualTo(1.0)
        assertThat(failureCount(registry, "put")).isZero()
        verify(kernel, times(1)).calculate(any(), any())
    }

    @Test
    fun `backend put failure records one bounded metric and preserves direct metadata`() {
        val kernel = deterministicKernel()
        val backend = RecordingBackend(putFailure = IllegalStateException("put failed"))
        val registry = SimpleMeterRegistry()
        val expectedTable = table()
        val cache = cache(kernel, expectedTable, backend, registry)

        val result = cache.getOrCalculate(input())

        assertThat(result.tableVersion).isEqualTo(expectedTable.version)
        assertThat(result.logicVersion).isEqualTo(ValuationKernel.LOGIC_VERSION)
        assertThat(failureCount(registry, "get")).isZero()
        assertThat(failureCount(registry, "put")).isEqualTo(1.0)
    }

    @Test
    fun `backend-reported get serialization failure increments exactly once`() {
        val registry = SimpleMeterRegistry()
        val backend = RecordingBackend(reportGetSerializationFailure = true)
        val cache = cache(deterministicKernel(), table(), backend, registry)

        cache.getOrCalculate(input())

        assertThat(failureCount(registry, "get")).isEqualTo(1.0)
        assertThat(failureCount(registry, "put")).isZero()
    }

    @Test
    fun `backend-reported put serialization failure increments exactly once`() {
        val registry = SimpleMeterRegistry()
        val backend = RecordingBackend(reportPutSerializationFailure = true)
        val cache = cache(deterministicKernel(), table(), backend, registry)

        cache.getOrCalculate(input())

        assertThat(failureCount(registry, "get")).isZero()
        assertThat(failureCount(registry, "put")).isEqualTo(1.0)
    }

    @Test
    fun `kernel failure propagates unchanged and is never cached or counted as cache failure`() {
        val kernel = mock<ValuationKernel>()
        val original = IllegalArgumentException("kernel invariant")
        whenever(kernel.calculate(any(), any())).thenThrow(original)
        val backend = RecordingBackend()
        val registry = SimpleMeterRegistry()
        val cache = cache(kernel, table(), backend, registry)

        val failure = catchThrowable { cache.getOrCalculate(input()) }

        assertThat(failure).isSameAs(original)
        assertThat(backend.putCalls).isZero()
        assertThat(failureCount(registry, "get")).isZero()
        assertThat(failureCount(registry, "put")).isZero()
    }

    @Test
    fun `kernel invariant after cache get failure propagates unchanged`() {
        val kernel = mock<ValuationKernel>()
        val original = ValuationInvariantException("kernel invariant")
        whenever(kernel.calculate(any(), any())).thenThrow(original)
        val backend = RecordingBackend(getFailure = IllegalStateException("get failed"))
        val registry = SimpleMeterRegistry()
        val cache = cache(kernel, table(), backend, registry)

        val failure = catchThrowable { cache.getOrCalculate(input()) }

        assertThat(failure).isSameAs(original)
        assertThat(backend.putCalls).isZero()
        assertThat(failureCount(registry, "get")).isEqualTo(1.0)
        assertThat(failureCount(registry, "put")).isZero()
    }

    private fun deterministicKernel(): ValuationKernel = mock<ValuationKernel>().also { kernel ->
        whenever(kernel.calculate(any(), any())).thenAnswer { invocation ->
            val input = invocation.getArgument<ValuationInput>(0)
            val table = invocation.getArgument<ProbabilityTableSnapshot>(1)
            ValuationResult(
                costs = ComponentCosts(null, null, null),
                trials = ComponentTrials(null, null),
                enhancePath = input.itemName,
                tableVersion = table.version,
                logicVersion = ValuationKernel.LOGIC_VERSION,
            )
        }
    }

    private fun cache(
        kernel: ValuationKernel,
        table: ProbabilityTableSnapshot,
        backend: RecordingBackend,
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
    ): ValuationCache = ValuationCache(kernel, table, backend, ValuationCacheMetrics(registry))

    private fun input(): ValuationInput = ValuationInput(
        itemName = "item",
        part = "모자",
        equipmentPart = "모자",
        itemLevel = 200,
        currentStar = 12,
        targetStar = 22,
        noljang = false,
        potentialGrade = "레전드리",
        potentialOptions = listOf("STR +12%", "STR +9%", "STR +9%"),
        additionalGrade = "유니크",
        additionalOptions = listOf("STR +18", "STR +14", "STR +14"),
    )

    private fun replace(values: List<String>, index: Int, replacement: String): List<String> =
        values.mapIndexed { current, value -> if (current == index) replacement else value }

    private fun table(
        logical: String = "csv-v1.0",
        sha: String = SHA_A,
    ): ProbabilityTableSnapshot = ProbabilityTableSnapshot(
        ProbabilityTableVersion(logical, sha),
        emptyMap(),
    )

    private fun failureCount(registry: SimpleMeterRegistry, operation: String): Double =
        registry.find(ValuationCacheMetrics.FAILURE_COUNTER)
            .tag("operation", operation)
            .counter()
            ?.count() ?: 0.0

    private class RecordingBackend(
        private val getFailure: RuntimeException? = null,
        private val putFailure: RuntimeException? = null,
        private val reportGetSerializationFailure: Boolean = false,
        private val reportPutSerializationFailure: Boolean = false,
    ) : OffHeapCacheBackend<ValuationCacheKey, ValuationResult> {
        private val values = mutableMapOf<ValuationCacheKey, ValuationResult>()
        private val errorCount = AtomicLong()
        var putCalls: Int = 0
            private set

        override fun get(key: ValuationCacheKey): ValuationResult? {
            getFailure?.let { failure -> throw failure }
            if (reportGetSerializationFailure) {
                errorCount.incrementAndGet()
                return null
            }
            return values[key]
        }

        override fun put(key: ValuationCacheKey, value: ValuationResult) {
            putCalls++
            putFailure?.let { failure -> throw failure }
            if (reportPutSerializationFailure) {
                errorCount.incrementAndGet()
                return
            }
            values[key] = value
        }

        override fun size(): Long = values.size.toLong()

        override fun stats(): CacheStats = CacheStats(values.size.toLong(), 0, 0, errorCount.get())

        override val name: String = "recording"

        override fun close() = values.clear()
    }

    private companion object {
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
