package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.externalapi.domain.KeyType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant

class ChunkFileManagerTest {

    private val objectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `appendSuccess accumulates records and rotates when limit hit`() {
        val storage = mock<ObjectStorage>()
        val keyCaptor = argumentCaptor<String>()
        whenever(storage.put(keyCaptor.capture(), any<ByteArray>()))
            .thenAnswer { invocation ->
                val key: String = invocation.getArgument(0)
                PutResult(key, 0L, null)
            }

        val manager = ChunkFileManager(
            runKey = "runs/testrun/ranking-overall",
            endpoint = "ranking-overall",
            maxRecords = 2,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            clock = Clock.systemUTC(),
            objectStorage = storage,
        )

        repeat(3) { i ->
            manager.appendSuccess(
                SnapshotChunkRecord.Success(
                    bodyBytes = objectMapper.writeValueAsBytes(mapOf("k" to "v$i")),
                    key = "k$i",
                    endpoint = "ranking-overall",
                    keyType = KeyType.DATE_PAGE.name,
                    httpStatus = 200,
                    fetchedAt = Instant.parse("2026-06-10T00:00:00Z"),
                ),
            )
        }
        // flush the open 2nd chunk to storage so the test sees both parts
        manager.closeCurrentChunk()

        // 3 records, maxRecords=2 → 1 rotation (after record 2 puts part-000001),
        // record 3 goes into part-000002 which is then closed above.
        val capturedKeys = keyCaptor.allValues
        assertThat(capturedKeys).hasSize(2)
        assertThat(capturedKeys[0]).isEqualTo("runs/testrun/ranking-overall/part-000001.jsonl.gz")
        assertThat(capturedKeys[1]).isEqualTo("runs/testrun/ranking-overall/part-000002.jsonl.gz")
        verify(storage, org.mockito.kotlin.times(2)).put(any<String>(), any<ByteArray>())
    }
}
