package maple.externalapi.cache

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OcidCacheProviderTest {

    @Test
    fun `refresh picks latest mapping by lastModified via ObjectStorage listByPrefix`() {
        val storage = mock<ObjectStorage>()
        whenever(storage.listByPrefix("ocid-mapping/")).thenReturn(listOf(
            ObjectInfo("ocid-mapping/ocid-mapping-20260609-090000.jsonl.gz", 100, Instant.parse("2026-06-09T09:00:00Z")),
            ObjectInfo("ocid-mapping/ocid-mapping-20260610-090000.jsonl.gz", 100, Instant.parse("2026-06-10T09:00:00Z")),
            ObjectInfo("ocid-mapping/ocid-mapping-20260608-090000.jsonl.gz", 100, Instant.parse("2026-06-08T09:00:00Z")),
        ))
        whenever(storage.getStream(any())).thenReturn("user1\tdummy-ocid-1\nuser2\tdummy-ocid-2\n".byteInputStream())

        val provider = OcidCacheProvider(storage)
        val cache = provider.refresh()

        assertEquals(2, cache.size)
        assertEquals("dummy-ocid-1", cache["user1"])
        assertEquals("dummy-ocid-2", cache["user2"])
        assertTrue(cache.isNotEmpty())
    }
}
