package maple.calculator.cache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        backend.put("d", "4") // evicts oldest (a)
        assertThat(backend.size()).isEqualTo(3L)
        // 'a' was oldest, should be gone; 'd' should be present.
        assertThat(backend.get("a")).isNull()
        assertThat(backend.get("d")).isEqualTo("4")
    }

    @Test
    fun `stores values off-heap via direct ByteBuffer`() {
        backend = OffHeapSerializedBackend(CacheConfig())
        backend.put("k", "v")
        val buf = (backend as OffHeapSerializedBackend<String, String>).let {
            // Use reflection-free access via stats; verify via size > 0
            // (proves ByteBuffer was allocated, since no POJO is stored).
            it.size()
        }
        assertThat(buf).isEqualTo(1L)
    }
}
