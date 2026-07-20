package maple.externalapi.artifact

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import maple.pipeline.artifact.write.ArtifactReceipt
import maple.pipeline.artifact.write.DefaultArtifactWriter
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class OcidMappingArtifactWriterTest {
    @Test
    fun `open writes one gzip session to the typed OCID mapping key`() {
        val storage = mock<ConditionalObjectStorage>()
        val storedPath = AtomicReference<Path?>()
        val storedBytes = AtomicReference<ByteArray?>()
        whenever(storage.putFileAsync(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val path = invocation.getArgument<Path>(1)
            val bytes = Files.readAllBytes(path)
            storedPath.set(path)
            storedBytes.set(bytes)
            CompletableFuture.completedFuture(PutResult(key, bytes.size.toLong(), "backend-tag"))
        }
        val writer = OcidMappingArtifactWriter(
            DefaultArtifactWriter(storage, java.util.concurrent.Executor { command -> command.run() }),
        )
        val line = "{\"userIgn\":\"ign-1\",\"ocid\":\"ocid-1\"}\n".toByteArray(Charsets.UTF_8)
        val session = writer.open("run-o-1")

        session.output.write(line)
        val receipt = awaitReceipt(session.complete(line.size.toLong()))

        assertThat(receipt.key.value).isEqualTo("ocid-mapping/ocid-mapping-run-o-1.jsonl.gz")
        assertThat(receipt.uncompressedBytes).isEqualTo(line.size.toLong())
        assertThat(receipt.backendTag).isEqualTo("backend-tag")
        assertThat(requireNotNull(storedPath.get())).doesNotExist()
        val uncompressed = GZIPInputStream(ByteArrayInputStream(requireNotNull(storedBytes.get())))
            .bufferedReader(Charsets.UTF_8)
            .readText()
        assertThat(uncompressed).isEqualTo(String(line, Charsets.UTF_8))
    }

    private fun awaitReceipt(future: CompletableFuture<ArtifactReceipt>): ArtifactReceipt {
        val receipt = AtomicReference<ArtifactReceipt?>()
        val failure = AtomicReference<Throwable?>()
        future.whenComplete { value, error ->
            receipt.set(value)
            failure.set(error)
        }
        await().atMost(Duration.ofSeconds(5)).until { receipt.get() != null || failure.get() != null }
        assertThat(failure.get()).isNull()
        return requireNotNull(receipt.get())
    }
}
