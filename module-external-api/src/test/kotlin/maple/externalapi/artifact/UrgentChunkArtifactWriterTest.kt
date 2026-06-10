package maple.externalapi.artifact

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.externalapi.domain.KeyType
import maple.externalapi.snapshot.SnapshotChunkRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * Migration Task 8: writer must put a single chunk at
 * `runs/{runId}/{endpointDir}/chunks/part-000001.jsonl.gz` and return
 * that key (not a local filesystem path).
 */
class UrgentChunkArtifactWriterTest {

    private val objectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())

    @Test
    fun `writeChunk returns runs slash runId slash endpoint slash chunks slash part key and puts to ObjectStorage`() {
        val storage = mock<ObjectStorage>()
        val keyCaptor = argumentCaptor<String>()
        val bytesCaptor = argumentCaptor<ByteArray>()
        whenever(storage.put(keyCaptor.capture(), bytesCaptor.capture()))
            .thenReturn(PutResult("k", 0, null))

        val writer = UrgentChunkArtifactWriter(
            objectMapper = objectMapper,
            objectStorage = storage,
        )
        val key = writer.writeChunk(
            runId = "abc",
            endpointDir = "ranking-overall",
            record = SnapshotChunkRecord.Success(
                bodyBytes = objectMapper.writeValueAsBytes(mapOf("character_name" to "user1")),
                key = "user1",
                endpoint = "ranking-overall",
                keyType = KeyType.USER_IGN.name,
                httpStatus = 200,
                fetchedAt = Instant.parse("2026-06-10T00:00:00Z"),
            ),
        )

        assertThat(key).isEqualTo("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz")
        assertThat(keyCaptor.firstValue).isEqualTo(key)
        assertThat(bytesCaptor.firstValue).isNotEmpty
        verify(storage).put(any<String>(), any<ByteArray>())
    }
}
