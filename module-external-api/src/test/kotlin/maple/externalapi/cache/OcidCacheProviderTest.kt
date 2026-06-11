package maple.externalapi.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import java.time.Instant

class OcidCacheProviderTest {

    private val objectMapper = ObjectMapper().registerModule(kotlinModule())

    @Test
    fun `refresh picks latest mapping by lastModified and parses JSONL entries`() {
        val storage = mock<ObjectStorage>()
        whenever(storage.listByPrefix("ocid-mapping/")).thenReturn(listOf(
            ObjectInfo("ocid-mapping/ocid-mapping-20260609-090000.jsonl.gz", 100, Instant.parse("2026-06-09T09:00:00Z")),
            ObjectInfo("ocid-mapping/ocid-mapping-20260610-090000.jsonl.gz", 100, Instant.parse("2026-06-10T09:00:00Z")),
            ObjectInfo("ocid-mapping/ocid-mapping-20260608-090000.jsonl.gz", 100, Instant.parse("2026-06-08T08:00:00Z")),
        ))
        val jsonl = """
            {"userIgn":"캐릭터A","ocid":"ocid-aaaa"}
            {"userIgn":"캐릭터B","ocid":"ocid-bbbb"}
        """.trimIndent()
        whenever(storage.getStream(any())).thenReturn(jsonl.byteInputStream())

        val provider = OcidCacheProvider(storage, objectMapper)
        val cache = provider.refresh()

        assertEquals(2, cache.size)
        assertEquals("ocid-aaaa", cache["캐릭터A"])
        assertEquals("ocid-bbbb", cache["캐릭터B"])
        assertTrue(cache.isNotEmpty())
    }

    @Test
    fun `refresh silently skips blank and malformed lines`() {
        val storage = mock<ObjectStorage>()
        whenever(storage.listByPrefix("ocid-mapping/")).thenReturn(listOf(
            ObjectInfo("ocid-mapping/ocid-mapping-20260610-090000.jsonl.gz", 100, Instant.parse("2026-06-10T09:00:00Z")),
        ))
        val jsonl = """
            {"userIgn":"캐릭터A","ocid":"ocid-aaaa"}

            this-is-not-json
            {"userIgn":"캐릭터B"}
            {"userIgn":"","ocid":"ocid-cccc"}
        """.trimIndent()
        whenever(storage.getStream(any())).thenReturn(jsonl.byteInputStream())

        val provider = OcidCacheProvider(storage, objectMapper)
        val cache = provider.refresh()

        assertEquals(1, cache.size)
        assertEquals("ocid-aaaa", cache["캐릭터A"])
    }

    @Test
    fun `refresh returns empty map when no ocid-mapping objects exist`() {
        val storage = mock<ObjectStorage>()
        whenever(storage.listByPrefix("ocid-mapping/")).thenReturn(emptyList())

        val provider = OcidCacheProvider(storage, objectMapper)
        val cache = provider.refresh()

        assertTrue(cache.isEmpty())
    }
}
