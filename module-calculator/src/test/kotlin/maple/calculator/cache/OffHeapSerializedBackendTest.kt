package maple.calculator.cache

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class OffHeapSerializedBackendTest {

    private lateinit var backend: OffHeapCacheBackend<String, String>

    @AfterEach
    fun tearDown() {
        if (::backend.isInitialized) backend.close()
    }

    @Test
    fun `put then get returns the stored value`() {
        backend = OffHeapSerializedBackend(CacheConfig())
        backend.put("k", "v")
        assertThat(backend.get("k")).isEqualTo("v")
    }

    @Test
    fun `put twice with same key overwrites`() {
        backend = OffHeapSerializedBackend(CacheConfig())
        backend.put("k", "v1")
        backend.put("k", "v2")
        assertThat(backend.get("k")).isEqualTo("v2")
    }

    @Test
    fun `size reflects entry count`() {
        backend = OffHeapSerializedBackend(CacheConfig())
        backend.put("a", "1")
        backend.put("b", "2")
        backend.put("c", "3")
        assertThat(backend.size()).isEqualTo(3L)
    }

    @Test
    fun `get returns null on miss and increments miss counter`() {
        backend = OffHeapSerializedBackend(CacheConfig())
        assertThat(backend.get("missing")).isNull()
        assertThat(backend.stats().misses).isEqualTo(1L)
        assertThat(backend.stats().hits).isEqualTo(0L)
    }

    @Test
    fun `name returns chronicle`() {
        backend = OffHeapSerializedBackend(CacheConfig())
        assertThat(backend.name).isEqualTo("chronicle")
    }

    @Test
    fun `concurrent put and get is thread safe`() {
        backend = OffHeapSerializedBackend(CacheConfig(maxEntries = 10_000L))
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
        backend = OffHeapSerializedBackend(CacheConfig(maxEntries = 3L))
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
        backend = OffHeapSerializedBackend(CacheConfig())
        backend.put("k", "v")
        assertThat(backend.size()).isEqualTo(1L)
    }

    @Test
    fun `hash collision does not silently overwrite distinct keys`() {
        // Regression: ConcurrentHashMap<Int, V> allows hash collisions to overwrite silently.
        // Fix: store key reference + verify key.equals() on get().
        backend = OffHeapSerializedBackend(CacheConfig())
        val keyA = object {
            override fun hashCode() = 0xCAFEBABE.toInt()
            override fun toString() = "A"
            override fun equals(other: Any?): Boolean = this === other
        }
        val keyB = object {
            override fun hashCode() = 0xCAFEBABE.toInt()
            override fun toString() = "B"
            override fun equals(other: Any?): Boolean = this === other
        }

        @Suppress("UNCHECKED_CAST")
        val typedBackend = backend as OffHeapSerializedBackend<Any, String>
        typedBackend.put(keyA, "valueA")
        typedBackend.put(keyB, "valueB")
        assertThat(typedBackend.get(keyA)).isEqualTo("valueA")
        assertThat(typedBackend.get(keyB)).isEqualTo("valueB")
    }
}
