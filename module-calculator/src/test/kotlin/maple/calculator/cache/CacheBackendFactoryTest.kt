package maple.calculator.cache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class CacheBackendFactoryTest {

    private val created = mutableListOf<OffHeapCacheBackend<*, *>>()

    @AfterEach
    fun tearDown() {
        created.forEach { it.close() }
        created.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <K : Any, V : Any> track(b: OffHeapCacheBackend<K, V>): OffHeapCacheBackend<K, V> {
        created.add(b as OffHeapCacheBackend<*, *>)
        return b
    }

    @Test
    fun `caffeine profile returns CaffeineCacheBackend`() {
        val b = track(CacheBackendFactory.create("caffeine", CacheConfig(), String::class.java, String::class.java))
        assertThat(b.name).isEqualTo("caffeine")
    }

    @Test
    fun `invalid profile falls back to caffeine`() {
        val b = track(CacheBackendFactory.create("redis", CacheConfig(), String::class.java, String::class.java))
        assertThat(b.name).isEqualTo("caffeine")
    }

    @Test
    fun `chronicle profile returns backend with name chronicle`() {
        // With Chronicle Map JDK 21-blocked (issue #1311 blocker), the stub is returned.
        // When upstream supports JDK 21, this test should still pass — name is always "chronicle"
        // (real impl or stub) when factory's chronicle branch executes.
        val b = track(CacheBackendFactory.create("chronicle", CacheConfig(), String::class.java, String::class.java))
        assertThat(b.name).isEqualTo("chronicle")
    }
}
