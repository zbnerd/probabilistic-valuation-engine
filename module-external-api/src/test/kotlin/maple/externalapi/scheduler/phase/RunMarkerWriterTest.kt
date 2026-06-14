package maple.externalapi.scheduler.phase

import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RunMarkerWriterTest {

    @Test
    fun `writeRunMarker puts marker to ObjectStorage with run key prefix`() {
        val storage = mock<ObjectStorage>()
        val keyCaptor = argumentCaptor<String>()
        val bytesCaptor = argumentCaptor<ByteArray>()
        whenever(storage.put(keyCaptor.capture(), bytesCaptor.capture()))
            .thenReturn(PutResult("k", 0L, null))

        val clock = Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC)
        val writer = RunMarkerWriter(clock, storage)
        writer.writeRunMarker("runs/20260610-120000-abc123")

        verify(storage).put(any<String>(), any<ByteArray>())
        assertEquals("runs/20260610-120000-abc123/_RUNNING", keyCaptor.firstValue)
        assertEquals("2026-06-10T12:00:00Z", String(bytesCaptor.firstValue))
    }
}
