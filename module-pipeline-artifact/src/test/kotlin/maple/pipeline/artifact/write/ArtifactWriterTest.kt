package maple.pipeline.artifact.write

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.config.ArtifactStorageAutoConfiguration
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.annotation.Bean

class ArtifactWriterTest {
    private val directExecutor = Executor { command -> command.run() }
    private val key = ArtifactKey.require("runs/run-1/ranking-overall/chunks/part-000001.jsonl.gz")

    @Test
    fun `serialization failure closes an open session and removes its temp file`() {
        val storage = mock<ConditionalObjectStorage>()
        val writer = DefaultArtifactWriter(storage, directExecutor)
        val before = artifactTempFiles()
        val serializationFailure = IllegalArgumentException("serialization failed")

        val observed = runCatching {
            writer.openGzip(key).use { session ->
                session.output.write("partial".toByteArray())
                throw serializationFailure
            }
        }.exceptionOrNull()

        assertThat(observed).isSameAs(serializationFailure)
        assertThat(artifactTempFiles()).isEqualTo(before)
        verify(storage, never()).putFileAsync(any(), any())
    }

    @Test
    fun `synchronous storage rejection returns failure and removes the temp file`() {
        val storage = mock<ConditionalObjectStorage>()
        val rejection = IllegalStateException("storage rejected upload")
        whenever(storage.putFileAsync(any(), any())).thenThrow(rejection)
        val writer = DefaultArtifactWriter(storage, directExecutor)
        val before = artifactTempFiles()
        val session = writer.openGzip(key)
        session.output.write("payload".toByteArray())

        val outcome = awaitCompletion(session.complete(uncompressedBytes = 7L))

        assertThat(rootCause(requireNotNull(outcome.failure))).isSameAs(rejection)
        assertThat(outcome.value).isNull()
        assertThat(artifactTempFiles()).isEqualTo(before)
    }

    @Test
    fun `asynchronous upload failure returns no receipt and removes the borrowed temp file`() {
        val storage = mock<ConditionalObjectStorage>()
        val upload = CompletableFuture<PutResult>()
        val borrowedPath = AtomicReference<Path?>()
        whenever(storage.putFileAsync(any(), any())).thenAnswer { invocation ->
            borrowedPath.set(invocation.getArgument(1))
            upload
        }
        val writer = DefaultArtifactWriter(storage, directExecutor)
        val before = artifactTempFiles()
        val session = writer.openGzip(key)
        session.output.write("payload".toByteArray())

        val receiptFuture = session.complete(uncompressedBytes = 7L)
        await().atMost(Duration.ofSeconds(5)).until { borrowedPath.get() != null }
        assertThat(requireNotNull(borrowedPath.get())).exists()
        val uploadFailure = IllegalStateException("upload failed")
        upload.completeExceptionally(uploadFailure)
        val outcome = awaitCompletion(receiptFuture)

        assertThat(rootCause(requireNotNull(outcome.failure))).isSameAs(uploadFailure)
        assertThat(outcome.value).isNull()
        assertThat(artifactTempFiles()).isEqualTo(before)
    }

    @Test
    fun `success returns receipt only after upload and removes the borrowed temp file`() {
        val storage = mock<ConditionalObjectStorage>()
        val upload = CompletableFuture<PutResult>()
        val borrowedPath = AtomicReference<Path?>()
        whenever(storage.putFileAsync(eq(key.value), any())).thenAnswer { invocation ->
            borrowedPath.set(invocation.getArgument(1))
            upload
        }
        val writer = DefaultArtifactWriter(storage, directExecutor)
        val before = artifactTempFiles()
        val session = writer.openGzip(key)
        session.output.write("payload".toByteArray())

        val receiptFuture = session.complete(uncompressedBytes = 7L)
        await().atMost(Duration.ofSeconds(5)).until { borrowedPath.get() != null }
        assertThat(receiptFuture.isDone).isFalse()
        val tempFile = requireNotNull(borrowedPath.get())
        val storedBytes = Files.readAllBytes(tempFile)
        upload.complete(PutResult(key.value, storedBytes.size.toLong(), "backend-etag"))
        val outcome = awaitCompletion(receiptFuture)
        val receipt = requireNotNull(outcome.value)

        assertThat(outcome.failure).isNull()
        assertThat(receipt.key).isEqualTo(key)
        assertThat(receipt.compressedBytes).isEqualTo(storedBytes.size.toLong())
        assertThat(receipt.uncompressedBytes).isEqualTo(7L)
        assertThat(receipt.contentSha256).isEqualTo(sha256(storedBytes))
        assertThat(receipt.backendTag).isEqualTo("backend-etag")
        assertThat(artifactTempFiles()).isEqualTo(before)
    }

    @Test
    fun `cancelling the returned receipt keeps internal cleanup alive`() {
        val storage = mock<ConditionalObjectStorage>()
        val upload = CompletableFuture<PutResult>()
        val borrowedPath = AtomicReference<Path?>()
        whenever(storage.putFileAsync(any(), any())).thenAnswer { invocation ->
            borrowedPath.set(invocation.getArgument(1))
            upload
        }
        val writer = DefaultArtifactWriter(storage, directExecutor)
        val session = writer.openGzip(key)
        session.output.write("payload".toByteArray())

        val exposedReceipt = session.complete(uncompressedBytes = 7L)
        await().atMost(Duration.ofSeconds(5)).until { borrowedPath.get() != null }
        val tempFile = requireNotNull(borrowedPath.get())

        try {
            assertThat(exposedReceipt.cancel(false)).isTrue()
            upload.complete(PutResult(key.value, Files.size(tempFile), "backend-etag"))
            await().atMost(Duration.ofSeconds(5)).until { Files.notExists(tempFile) }
            assertThat(exposedReceipt.isCancelled).isTrue()
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `identical compressed bytes have equal content hash when backend checksums differ`() {
        val storage = mock<ConditionalObjectStorage>()
        val callCount = AtomicInteger()
        val storedBytes = mutableListOf<ByteArray>()
        whenever(storage.putFileAsync(any(), any())).thenAnswer { invocation ->
            val uploadKey = invocation.getArgument<String>(0)
            val path = invocation.getArgument<Path>(1)
            val bytes = Files.readAllBytes(path)
            storedBytes.add(bytes)
            val call = callCount.incrementAndGet()
            CompletableFuture.completedFuture(PutResult(uploadKey, bytes.size.toLong(), "backend-$call"))
        }
        val writer = DefaultArtifactWriter(storage, directExecutor)

        val first = writePayload(writer, key, "same-payload")
        val second = writePayload(writer, key, "same-payload")

        assertThat(storedBytes).hasSize(2)
        assertThat(storedBytes[0]).containsExactly(*storedBytes[1])
        assertThat(first.contentSha256).isEqualTo(second.contentSha256)
        assertThat(first.contentSha256).isEqualTo(sha256(storedBytes[0]))
        assertThat(first.backendTag).isEqualTo("backend-1")
        assertThat(second.backendTag).isEqualTo("backend-2")
    }

    @Test
    fun `only one terminal transition succeeds and close aborts an open session`() {
        val storage = mock<ConditionalObjectStorage>()
        whenever(storage.putFileAsync(any(), any())).thenAnswer { invocation ->
            val uploadKey = invocation.getArgument<String>(0)
            val path = invocation.getArgument<Path>(1)
            CompletableFuture.completedFuture(PutResult(uploadKey, Files.size(path), null))
        }
        val writer = DefaultArtifactWriter(storage, directExecutor)
        val completed = writer.openGzip(key)
        completed.output.write("payload".toByteArray())
        assertThat(awaitCompletion(completed.complete(7L)).failure).isNull()

        assertThat(awaitCompletion(completed.complete(7L)).failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(awaitCompletion(completed.abort(IllegalArgumentException("too late"))).failure)
            .isInstanceOf(IllegalStateException::class.java)

        val before = artifactTempFiles()
        writer.openGzip(key).close()
        assertThat(artifactTempFiles()).isEqualTo(before)
    }

    @Test
    fun `auto configuration declares exactly one ArtifactWriter bean`() {
        val writerBeans = ArtifactStorageAutoConfiguration::class.java.declaredMethods.filter { method ->
            method.returnType == ArtifactWriter::class.java && method.getAnnotation(Bean::class.java) != null
        }

        assertThat(writerBeans).hasSize(1)
    }

    private fun writePayload(writer: ArtifactWriter, artifactKey: ArtifactKey, payload: String): ArtifactReceipt {
        val session = writer.openGzip(artifactKey)
        val bytes = payload.toByteArray()
        session.output.write(bytes)
        val outcome = awaitCompletion(session.complete(bytes.size.toLong()))
        assertThat(outcome.failure).isNull()
        return requireNotNull(outcome.value)
    }

    private fun <T> awaitCompletion(future: CompletableFuture<T>): Completion<T> {
        val observed = AtomicReference<Completion<T>?>()
        future.whenComplete { value, failure -> observed.set(Completion(value, failure)) }
        await().atMost(Duration.ofSeconds(5)).until { observed.get() != null }
        return requireNotNull(observed.get())
    }

    private fun artifactTempFiles(): Set<Path> {
        val systemTempDirectory = Path.of(System.getProperty("java.io.tmpdir"))
        return Files.list(systemTempDirectory).use { paths ->
            paths.filter { path -> path.fileName.toString().startsWith("artifact-gzip-") }
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList()
                .toSet()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun rootCause(failure: Throwable): Throwable = generateSequence(failure) { current -> current.cause }.last()

    private data class Completion<T>(
        val value: T?,
        val failure: Throwable?,
    )
}
