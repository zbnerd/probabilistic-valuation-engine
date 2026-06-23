package maple.calculator.cache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class CaffeineCacheBackendTest {

    private lateinit var backend: OffHeapCacheBackend<String, String>

    @AfterEach
    fun tearDown() {
        if (::backend.isInitialized) backend.close()
    }

    @Test
    fun `put then get returns the stored value`() {
        backend = CaffeineCacheBackend(CacheConfig())
        backend.put("key1", "value1")
        assertThat(backend.get("key1")).isEqualTo("value1")
    }

    @Test
    fun `put twice with same key overwrites`() {
        backend = CaffeineCacheBackend(CacheConfig())
        backend.put("k", "v1")
        backend.put("k", "v2")
        assertThat(backend.get("k")).isEqualTo("v2")
    }

    @Test
    fun `size reflects entry count`() {
        backend = CaffeineCacheBackend(CacheConfig())
        backend.put("a", "1")
        backend.put("b", "2")
        backend.put("c", "3")
        assertThat(backend.size()).isEqualTo(3L)
    }

    @Test
    fun `get returns null on miss and increments miss counter`() {
        backend = CaffeineCacheBackend(CacheConfig())
        assertThat(backend.get("missing")).isNull()
        assertThat(backend.stats().misses).isEqualTo(1L)
        assertThat(backend.stats().hits).isEqualTo(0L)
    }

    @Test
    fun `name returns caffeine`() {
        backend = CaffeineCacheBackend(CacheConfig())
        assertThat(backend.name).isEqualTo("caffeine")
    }
}
