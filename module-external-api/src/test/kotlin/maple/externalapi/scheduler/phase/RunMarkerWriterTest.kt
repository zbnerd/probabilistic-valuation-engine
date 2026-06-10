package maple.externalapi.scheduler.phase

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class RunMarkerWriterTest {

    @Test
    fun `writeRunMarker puts marker to ObjectStorage with run key prefix`() {
        val storage = mockk<ObjectStorage>()
        val key = slot<String>()
        val bytes = slot<ByteArray>()
        every { storage.put(capture(key), capture(bytes)) } returns PutResult("k", 0, null)

        val clock = Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC)
        val writer = RunMarkerWriter(clock, storage)
        writer.writeRunMarker("runs/20260610-120000-abc123")

        verify(exactly = 1) { storage.put(any(), any()) }
        assertEquals("runs/20260610-120000-abc123/_RUNNING", key.captured)
        assertEquals("2026-06-10T12:00:00Z", String(bytes.captured))
    }
}
