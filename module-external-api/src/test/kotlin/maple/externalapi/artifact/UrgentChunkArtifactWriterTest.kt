package maple.externalapi.artifact

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.common.storage.PutResult
import maple.externalapi.domain.KeyType
import maple.externalapi.snapshot.SnapshotChunkRecord
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import maple.pipeline.artifact.write.ArtifactReceipt
import maple.pipeline.artifact.write.DefaultArtifactWriter
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Migration Task 8: writer must put a single chunk under
 * `runs/{runId}/{endpointDir}/chunks/` and return its receipt (not a local
 * filesystem path or a pre-upload key). The part suffix is a UUID, not a
 * counter — this prevents concurrent urgent writes for the same runId and
 * endpoint from clobbering each other.
 */
class UrgentChunkArtifactWriterTest {

    private val objectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())

    @Test
    fun `writeChunk returns runs slash runId slash endpoint slash chunks slash part uuid key and puts to ObjectStorage`() {
        val storage = mock<ConditionalObjectStorage>()
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
            artifactWriter = DefaultArtifactWriter(
                storage,
                java.util.concurrent.Executor { command -> command.run() },
            ),
        )
        val receipt = awaitReceipt(
            writer.writeChunk(
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
            ),
        )

        assertThat(receipt.key.value).matches("^runs/abc/ranking-overall/chunks/part-[0-9a-f-]{36}\\.jsonl\\.gz$")
        assertThat(keyCaptor.firstValue).isEqualTo(receipt.key.value)
        assertThat(receipt.compressedBytes).isEqualTo(captured.size.toLong())
        assertThat(captured).isNotEmpty
        verify(storage).putFileAsync(any<String>(), any<Path>())
    }

    private fun awaitReceipt(stage: java.util.concurrent.CompletionStage<ArtifactReceipt>): ArtifactReceipt {
        val receipt = AtomicReference<ArtifactReceipt?>()
        val failure = AtomicReference<Throwable?>()
        stage.whenComplete { value, error ->
            receipt.set(value)
            failure.set(error)
        }
        await().atMost(Duration.ofSeconds(5)).until { receipt.get() != null || failure.get() != null }
        assertThat(failure.get()).isNull()
        return requireNotNull(receipt.get())
    }
}
