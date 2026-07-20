package maple.calculator.cache

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import maple.calculator.processor.ValuationCacheKey
import maple.expectation.core.calculation.ComponentCosts
import maple.expectation.core.calculation.ComponentTrials
import maple.expectation.core.calculation.ValuationInput
import maple.expectation.core.calculation.ValuationKernel
import maple.expectation.core.calculation.ValuationResult
import maple.expectation.core.calculation.probability.ProbabilityTableVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class OffHeapSerializedBackendTest {

    private val mapper = jacksonObjectMapper()
    private lateinit var backend: OffHeapCacheBackend<String, String>

    @AfterEach
    fun tearDown() {
        if (::backend.isInitialized) backend.close()
    }

    @Test
    fun `put then get returns the stored value`() {
        backend = stringBackend()
        backend.put("k", "v")
        assertThat(backend.get("k")).isEqualTo("v")
    }

    @Test
    fun `put twice with same key overwrites`() {
        backend = stringBackend()
        backend.put("k", "v1")
        backend.put("k", "v2")
        assertThat(backend.get("k")).isEqualTo("v2")
    }

    @Test
    fun `size reflects entry count`() {
        backend = stringBackend()
        backend.put("a", "1")
        backend.put("b", "2")
        backend.put("c", "3")
        assertThat(backend.size()).isEqualTo(3L)
    }

    @Test
    fun `get returns null on miss and increments miss counter`() {
        backend = stringBackend()
        assertThat(backend.get("missing")).isNull()
        assertThat(backend.stats().misses).isEqualTo(1L)
        assertThat(backend.stats().hits).isEqualTo(0L)
    }

    @Test
    fun `name returns chronicle`() {
        backend = stringBackend()
        assertThat(backend.name).isEqualTo("chronicle")
    }

    @Test
    fun `concurrent put and get is thread safe`() {
        backend = stringBackend(CacheConfig(maxEntries = 10_000L))
        val threads = 4
        val opsPerThread = 200
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(opsPerThread) { i ->
                    val k = "t$t-i$i"
                    backend.put(k, "v$i")
                    assertThat(backend.get(k)).isNotNull()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertThat(done.await(15, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        assertThat(backend.size()).isEqualTo((threads * opsPerThread).toLong())
    }

    @Test
    fun `eviction at max entries drops oldest`() {
        backend = stringBackend(CacheConfig(maxEntries = 3L))
        backend.put("a", "1")
        backend.put("b", "2")
        backend.put("c", "3")
        assertThat(backend.size()).isEqualTo(3L)
        backend.put("d", "4")
        assertThat(backend.size()).isEqualTo(3L)
        assertThat(backend.get("a")).isNull()
        assertThat(backend.get("d")).isEqualTo("4")
    }

    @Test
    fun `stores values off-heap via direct ByteBuffer`() {
        backend = stringBackend()
        backend.put("k", "v")
        assertThat(backend.size()).isEqualTo(1L)
    }

    @Test
    fun `equal but distinct canonical valuation keys hit the same entry`() {
        val typedBackend = valuationBackend()
        val firstKey = valuationKey()
        val equalDistinctKey = firstKey.copy(input = firstKey.input.copy())
        val value = valuationResult()

        typedBackend.put(firstKey, value)

        assertThat(typedBackend.get(equalDistinctKey)).isEqualTo(value)
        assertThat(typedBackend.stats().hits).isEqualTo(1L)
        typedBackend.close()
    }

    @Test
    fun `unequal canonical valuation keys do not collide`() {
        val typedBackend = valuationBackend()
        val firstKey = valuationKey()
        val otherKey = firstKey.copy(input = firstKey.input.copy(itemName = "other"))

        typedBackend.put(firstKey, valuationResult())

        assertThat(typedBackend.get(otherKey)).isNull()
        assertThat(typedBackend.stats().misses).isEqualTo(1L)
        typedBackend.close()
    }

    @Test
    fun `valuation result decodes to its concrete type`() {
        val typedBackend = valuationBackend()
        val value = valuationResult()
        typedBackend.put(valuationKey(), value)

        val decoded = typedBackend.get(valuationKey())

        assertThat(decoded).isInstanceOf(ValuationResult::class.java)
        assertThat(decoded).isEqualTo(value)
        typedBackend.close()
    }

    private fun stringBackend(config: CacheConfig = CacheConfig()): OffHeapSerializedBackend<String, String> =
        OffHeapSerializedBackend(config, mapper, String::class.java, String::class.java)

    private fun valuationBackend(): OffHeapSerializedBackend<ValuationCacheKey, ValuationResult> =
        OffHeapSerializedBackend(
            CacheConfig(),
            mapper,
            ValuationCacheKey::class.java,
            ValuationResult::class.java,
        )

    private fun valuationKey(): ValuationCacheKey = ValuationCacheKey(
        input = ValuationInput(
            itemName = "item",
            part = "무기",
            equipmentPart = "무기",
            itemLevel = 200,
            currentStar = 0,
            targetStar = 22,
            noljang = false,
            potentialGrade = "레전드리",
            potentialOptions = listOf("공격력 +12%", "공격력 +9%", "공격력 +9%"),
            additionalGrade = null,
            additionalOptions = emptyList(),
        ),
        tableLogicalVersion = "csv-v1.0",
        tableContentSha256 = "a".repeat(64),
        logicVersion = ValuationKernel.LOGIC_VERSION,
    )

    private fun valuationResult(): ValuationResult = ValuationResult(
        costs = ComponentCosts(1.0, null, 2.0),
        trials = ComponentTrials(3.0, null),
        enhancePath = "item > 블랙큐브(윗잠) > 스타포스(0→22성)",
        tableVersion = ProbabilityTableVersion("csv-v1.0", "a".repeat(64)),
        logicVersion = ValuationKernel.LOGIC_VERSION,
    )
}
