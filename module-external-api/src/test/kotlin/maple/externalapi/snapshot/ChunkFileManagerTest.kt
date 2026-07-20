package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import maple.expectation.common.storage.PutResult
import maple.externalapi.domain.KeyType
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import maple.pipeline.artifact.write.DefaultArtifactWriter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ChunkFileManagerTest {

    private val objectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `cleanupOnFailure deletes all objects under runKey prefix and the running marker`() {
        val storage = mock<ConditionalObjectStorage>()
        val prefixCaptor = argumentCaptor<String>()
        whenever(storage.deleteByPrefix(prefixCaptor.capture())).thenReturn(5L)

        val manager = ChunkFileManager(
            runId = "abc",
            endpoint = "ranking-overall",
            maxRecords = 100,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            clock = Clock.systemUTC(),
            objectStorage = storage,
            artifactWriter = artifactWriter(storage),
        )

        manager.cleanupOnFailure()

        assertThat(prefixCaptor.firstValue).isEqualTo("runs/abc/ranking-overall")
        verify(storage).delete("runs/abc/ranking-overall/_RUNNING")
    }

    @Test
    fun `deleteRunningMarker removes the running marker under runKey`() {
        val storage = mock<ConditionalObjectStorage>()
        val keyCaptor = argumentCaptor<String>()

        val manager = ChunkFileManager(
            runId = "abc",
            endpoint = "ranking-overall",
            maxRecords = 100,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            clock = Clock.systemUTC(),
            objectStorage = storage,
            artifactWriter = artifactWriter(storage),
        )

        manager.deleteRunningMarker()

        verify(storage).delete(keyCaptor.capture())
        assertThat(keyCaptor.firstValue).isEqualTo("runs/abc/ranking-overall/_RUNNING")
    }

    @Test
    fun `appendSuccess accumulates records and rotates when limit hit`() {
        val storage = mock<ConditionalObjectStorage>()
        val keyCaptor = argumentCaptor<String>()
        whenever(storage.putFileAsync(keyCaptor.capture(), any<Path>()))
            .thenAnswer { invocation ->
                val key: String = invocation.getArgument(0)
                val path: Path = invocation.getArgument(1)
                val bytes = Files.readAllBytes(path)
                java.util.concurrent.CompletableFuture.completedFuture(
                    PutResult(key, bytes.size.toLong(), null),
                )
            }

        val manager = ChunkFileManager(
            runId = "testrun",
            endpoint = "ranking-overall",
            maxRecords = 2,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            clock = Clock.systemUTC(),
            objectStorage = storage,
            artifactWriter = artifactWriter(storage),
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
        assertThat(capturedKeys[0]).isEqualTo("runs/testrun/ranking-overall/chunks/part-000001.jsonl.gz")
        assertThat(capturedKeys[1]).isEqualTo("runs/testrun/ranking-overall/chunks/part-000002.jsonl.gz")
        verify(storage, org.mockito.kotlin.times(2)).putFileAsync(any<String>(), any<Path>())
    }

    @Test
    fun `serialization failure leaves the active chunk usable for the next record`() {
        val storage = mock<ConditionalObjectStorage>()
        val serializedRecords = AtomicInteger()
        val serializationFailure = IllegalArgumentException("serialization failed")
        val flakyMapper = object : ObjectMapper() {
            override fun writeValueAsBytes(value: Any?): ByteArray {
                if (serializedRecords.incrementAndGet() == 2) throw serializationFailure
                return objectMapper.writeValueAsBytes(value)
            }
        }
        val borrowedPath = AtomicReference<Path?>()
        val uploadedBytes = AtomicReference<ByteArray?>()
        whenever(storage.putFileAsync(any<String>(), any<Path>())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val path = invocation.getArgument<Path>(1)
            val bytes = Files.readAllBytes(path)
            borrowedPath.set(path)
            uploadedBytes.set(bytes)
            java.util.concurrent.CompletableFuture.completedFuture(PutResult(key, bytes.size.toLong(), null))
        }
        val manager = ChunkFileManager(
            runId = "serialization-recovery",
            endpoint = "ranking-overall",
            maxRecords = 100,
            maxUncompressedBytes = 1_000_000,
            objectMapper = flakyMapper,
            clock = Clock.systemUTC(),
            objectStorage = storage,
            artifactWriter = artifactWriter(storage),
        )

        manager.appendSuccess(successRecord("first"))
        assertThatThrownBy { manager.appendSuccess(successRecord("rejected")) }
            .isSameAs(serializationFailure)
        manager.appendSuccess(successRecord("second"))
        manager.closeCurrentChunk()

        assertThat(manager.awaitAllUploads()).isTrue()
        assertThat(requireNotNull(borrowedPath.get())).doesNotExist()
        val records = GZIPInputStream(ByteArrayInputStream(requireNotNull(uploadedBytes.get())))
            .bufferedReader()
            .readLines()
        assertThat(records).hasSize(2)
        assertThat(records).anySatisfy { line -> assertThat(line).contains("first") }
        assertThat(records).anySatisfy { line -> assertThat(line).contains("second") }
    }

    private fun successRecord(key: String): SnapshotChunkRecord.Success = SnapshotChunkRecord.Success(
        bodyBytes = objectMapper.writeValueAsBytes(mapOf("key" to key)),
        key = key,
        endpoint = "ranking-overall",
        keyType = KeyType.DATE_PAGE.name,
        httpStatus = 200,
        fetchedAt = Instant.parse("2026-06-10T00:00:00Z"),
    )

    private fun artifactWriter(storage: ConditionalObjectStorage): DefaultArtifactWriter = DefaultArtifactWriter(
        storage,
        java.util.concurrent.Executor { command -> command.run() },
    )
}
