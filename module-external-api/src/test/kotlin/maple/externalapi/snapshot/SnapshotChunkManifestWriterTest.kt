package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

class SnapshotChunkManifestWriterTest {

    @Test
    fun `write puts manifest JSON to ObjectStorage under runKey path`() {
        val storage = mock<ObjectStorage>()
        val objectMapper = ObjectMapper().registerModules(kotlinModule(), JavaTimeModule())
        whenever(storage.put(any(), any<ByteArray>())).thenReturn(PutResult("k", 0, null))

        val writer = SnapshotChunkManifestWriter(objectMapper, storage)
        val manifest = SnapshotChunkManifest(
            runId = "20260610-120000-abc",
            endpoint = "ranking-overall",
            startedAt = Instant.parse("2026-06-10T12:00:00Z"),
            finishedAt = Instant.parse("2026-06-10T12:30:00Z"),
            chunks = mutableListOf(
                ChunkEntry(
                    path = "part-000001.jsonl.gz",
                    recordCount = 50,
                    uncompressedBytes = 5000,
                    compressedBytes = 1000,
                    startedAt = Instant.parse("2026-06-10T12:00:00Z"),
                    finishedAt = Instant.parse("2026-06-10T12:30:00Z"),
                ),
            ),
            totalRecords = 100,
            totalFailed = 5,
        )

        writer.write("runs/20260610-120000-abc/ranking-overall", manifest)

        val keyCaptor = argumentCaptor<String>()
        val bytesCaptor = argumentCaptor<ByteArray>()
        verify(storage).put(keyCaptor.capture(), bytesCaptor.capture())

        assertEquals("runs/20260610-120000-abc/ranking-overall/manifest.json", keyCaptor.firstValue)
        val json = String(bytesCaptor.firstValue)
        assertTrue(json.contains("\"runId\":\"20260610-120000-abc\"")) { "json=$json" }
        assertTrue(json.contains("\"totalRecords\":100")) { "json=$json" }
    }
}
