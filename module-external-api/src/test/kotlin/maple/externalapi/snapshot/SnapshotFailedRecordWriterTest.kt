package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.time.Instant
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.identity.SourceArtifactLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SnapshotFailedRecordWriterTest {

    @Test
    fun `append reads existing, appends, and writes back to ObjectStorage`() {
        val storage = mock<ObjectStorage>()
        val objectMapper = ObjectMapper().registerModules(kotlinModule(), JavaTimeModule())
        whenever(storage.get(any())).thenReturn(ByteArray(0)) // empty initial
        whenever(storage.put(any(), any<ByteArray>())).thenReturn(PutResult("k", 0, null))

        val writer = SnapshotFailedRecordWriter(
            failedKey = SourceArtifactLayout.failedRecords("test", "ranking-overall"),
            objectMapper = objectMapper,
            objectStorage = storage,
        )

        writer.append(
            SnapshotChunkRecord.Failure(
                key = "k1",
                endpoint = "ranking-overall",
                keyType = "DATE_PAGE",
                httpStatus = 500,
                fetchedAt = Instant.parse("2026-06-10T00:00:00Z"),
                errorMessage = "boom",
            ),
        )

        assertEquals(1, writer.count())
        val bytesCaptor = argumentCaptor<ByteArray>()
        verify(storage).put(any(), bytesCaptor.capture())
        assertTrue(String(bytesCaptor.firstValue).contains("\"errorMessage\":\"boom\""))
    }
}
