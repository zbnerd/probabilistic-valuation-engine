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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Migration Task 8: writer must put a single chunk under
 * `runs/{runId}/{endpointDir}/chunks/` and return that key (not a local
 * filesystem path). The part suffix is a UUID, not a counter — this prevents
 * concurrent urgent writes for the same runId/endpoint from clobbering each
 * other on the same key.
 */
class UrgentChunkArtifactWriterTest {

    private val objectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())

    @Test
    fun `writeChunk returns runs slash runId slash endpoint slash chunks slash part uuid key and puts to ObjectStorage`() {
        val storage = mock<ObjectStorage>()
        val keyCaptor = argumentCaptor<String>()
        var captured: ByteArray = ByteArray(0)
        whenever(storage.putFileAsync(keyCaptor.capture(), any<Path>()))
            .thenAnswer { invocation ->
                val key: String = invocation.getArgument(0)
                val path: Path = invocation.getArgument(1)
                captured = Files.readAllBytes(path)
                java.util.concurrent.CompletableFuture.completedFuture(
                    PutResult(key, captured.size.toLong(), null),
                )
            }

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

        assertThat(key).matches("^runs/abc/ranking-overall/chunks/part-[0-9a-f-]{36}\\.jsonl\\.gz$")
        assertThat(keyCaptor.firstValue).isEqualTo(key)
        assertThat(captured).isNotEmpty
        verify(storage).putFileAsync(any<String>(), any<Path>())
    }
}
