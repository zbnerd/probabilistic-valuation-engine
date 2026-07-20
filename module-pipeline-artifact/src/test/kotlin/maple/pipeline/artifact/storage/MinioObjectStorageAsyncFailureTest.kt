package maple.pipeline.artifact.storage

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.transfer.s3.S3TransferManager
import software.amazon.awssdk.transfer.s3.model.UploadRequest

class MinioObjectStorageAsyncFailureTest {
    @TempDir
    lateinit var tempDir: Path

    private val syncClient = mock<S3Client>()
    private val asyncClient = mock<S3AsyncClient>()
    private val transferManager = mock<S3TransferManager>()
    private val streamReaderExecutor = mock<ExecutorService>()
    private val storage = MinioObjectStorage(
        properties = MinioProperties(
            endpoint = "http://minio:9000",
            accessKey = "test-access-key",
            bucket = "test-bucket",
        ),
        s3Client = syncClient,
        s3AsyncClient = asyncClient,
        transferManager = transferManager,
        streamReaderExecutor = streamReaderExecutor,
        meterRegistry = null,
    )

    @Test
    fun `missing putFileAsync source returns a failed future`() {
        val missing = tempDir.resolve("missing.bin")

        val upload = storage.putFileAsync("objects/missing.bin", missing)

        assertFailure(upload, IllegalArgumentException::class.java)
        assertThat(Files.exists(missing)).isFalse()
    }

    @Test
    fun `synchronous transfer manager failure returns a failed future and preserves caller file`() {
        val source = tempDir.resolve("caller.bin")
        Files.writeString(source, "caller-owned")
        val setupFailure = IllegalStateException("upload setup failed")
        whenever(transferManager.upload(any<UploadRequest>())).thenThrow(setupFailure)

        val upload = storage.putFileAsync("objects/caller.bin", source)

        assertFailure(upload, setupFailure)
        assertThat(Files.readString(source)).isEqualTo("caller-owned")
    }

    @Test
    fun `synchronous stream upload failure returns a failed future without closing caller stream`() {
        val callerStream = TrackingInputStream("payload".toByteArray())
        val setupFailure = IllegalStateException("stream setup failed")
        whenever(asyncClient.putObject(any<PutObjectRequest>(), any<AsyncRequestBody>()))
            .thenThrow(setupFailure)

        val upload = storage.putStreamMultipart("objects/stream.bin", callerStream)

        assertFailure(upload, setupFailure)
        assertThat(callerStream.closed).isFalse()
    }

    @Test
    fun `synchronous conditional put failure returns a failed future`() {
        val setupFailure = IllegalStateException("conditional setup failed")
        whenever(asyncClient.putObject(any<PutObjectRequest>(), any<AsyncRequestBody>()))
            .thenThrow(setupFailure)

        val write = storage.putIfAbsent("objects/conditional.bin", "payload".toByteArray())

        assertFailure(write, setupFailure)
    }

    @Test
    fun `nested precondition failure reads existing bytes and preserves backend tag`() {
        val existingBytes = "existing".toByteArray()
        val preconditionFailure = S3Exception.builder().statusCode(412).message("exists").build()
        whenever(asyncClient.putObject(any<PutObjectRequest>(), any<AsyncRequestBody>()))
            .thenReturn(
                CompletableFuture.failedFuture(
                    CompletionException(ExecutionException(preconditionFailure)),
                ),
            )
        whenever(
            asyncClient.getObject(
                any<GetObjectRequest>(),
                any<AsyncResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>(),
            ),
        ).thenReturn(
            CompletableFuture.completedFuture(
                ResponseBytes.fromByteArray(
                    GetObjectResponse.builder().eTag("existing-tag").build(),
                    existingBytes,
                ),
            ),
        )
        val result = AtomicReference<PutIfAbsentResult?>()

        storage.putIfAbsent("objects/existing.bin", "new".toByteArray())
            .whenComplete { value, _ -> result.set(value) }

        await().until { result.get() != null }
        val existing = result.get()
        assertThat(existing).isInstanceOf(PutIfAbsentResult.Existing::class.java)
        if (existing is PutIfAbsentResult.Existing) {
            assertThat(existing.bytes).isEqualTo(existingBytes)
            assertThat(existing.backendTag).isEqualTo("existing-tag")
        }
    }

    @Test
    fun `non-precondition conditional failure does not read existing object`() {
        val backendFailure = S3Exception.builder().statusCode(503).message("unavailable").build()
        whenever(asyncClient.putObject(any<PutObjectRequest>(), any<AsyncRequestBody>()))
            .thenReturn(
                CompletableFuture.failedFuture(
                    CompletionException(ExecutionException(backendFailure)),
                ),
            )

        val write = storage.putIfAbsent("objects/unavailable.bin", "payload".toByteArray())

        assertFailure(write, backendFailure)
        verify(asyncClient, never()).getObject(
            any<GetObjectRequest>(),
            any<AsyncResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>(),
        )
    }

    @Test
    fun `synchronous existing read failure returns a failed future`() {
        val preconditionFailure = S3Exception.builder().statusCode(412).message("exists").build()
        val readSetupFailure = IllegalStateException("read setup failed")
        whenever(asyncClient.putObject(any<PutObjectRequest>(), any<AsyncRequestBody>()))
            .thenReturn(CompletableFuture.failedFuture(preconditionFailure))
        whenever(
            asyncClient.getObject(
                any<GetObjectRequest>(),
                any<AsyncResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>(),
            ),
        ).thenThrow(readSetupFailure)

        val write = storage.putIfAbsent("objects/existing.bin", "payload".toByteArray())

        assertFailure(write, readSetupFailure)
    }

    @Test
    fun `successful conditional put preserves created backend tag`() {
        whenever(asyncClient.putObject(any<PutObjectRequest>(), any<AsyncRequestBody>()))
            .thenReturn(
                CompletableFuture.completedFuture(
                    PutObjectResponse.builder().eTag("created-tag").build(),
                ),
            )
        val result = AtomicReference<PutIfAbsentResult?>()

        storage.putIfAbsent("objects/new.bin", "payload".toByteArray())
            .whenComplete { value, _ -> result.set(value) }

        await().until { result.get() != null }
        assertThat(result.get()).isEqualTo(PutIfAbsentResult.Created("created-tag"))
    }

    private fun assertFailure(stage: CompletionStage<*>, expected: Throwable) {
        val observedFailure = AtomicReference<Throwable?>()
        stage.whenComplete { _, failure -> observedFailure.set(failure) }

        await().until { observedFailure.get() != null }

        assertThat(unwrap(requireNotNull(observedFailure.get()))).isSameAs(expected)
    }

    private fun assertFailure(stage: CompletionStage<*>, expectedType: Class<out Throwable>) {
        val observedFailure = AtomicReference<Throwable?>()
        stage.whenComplete { _, failure -> observedFailure.set(failure) }

        await().until { observedFailure.get() != null }

        assertThat(unwrap(requireNotNull(observedFailure.get()))).isInstanceOf(expectedType)
    }

    private fun unwrap(failure: Throwable): Throwable {
        val cause = failure.cause
        return if ((failure is CompletionException || failure is ExecutionException) && cause != null) {
            unwrap(cause)
        } else {
            failure
        }
    }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
