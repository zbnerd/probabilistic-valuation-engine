package maple.pipeline.artifact.storage

import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.transfer.s3.S3TransferManager

/**
 * Integration test for MinioObjectStorage. Runs only when INTEGRATION_MINIO=true.
 * Requires a running MinIO at MINIO_ENDPOINT (default http://localhost:9000) with
 * valid MINIO_ACCESS_KEY/MINIO_SECRET_KEY.
 */
@EnabledIfEnvironmentVariable(named = "INTEGRATION_MINIO", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioObjectStorageIT : ObjectStorageContract() {

    private lateinit var s3: S3Client
    private lateinit var s3Async: S3AsyncClient
    private lateinit var transferManager: S3TransferManager
    private lateinit var endpoint: String
    private lateinit var accessKey: String
    private lateinit var bucket: String
    private lateinit var testPrefix: String
    private lateinit var storage: MinioObjectStorage
    private lateinit var streamReaderExecutor: ExecutorService

    @BeforeAll
    fun setUp() {
        endpoint = System.getenv("MINIO_ENDPOINT") ?: "http://localhost:9000"
        accessKey = requiredEnvironment("MINIO_ACCESS_KEY")
        val secretKey = requiredEnvironment("MINIO_SECRET_KEY")
        val region = System.getenv("MINIO_REGION") ?: "us-east-1"
        bucket = System.getenv("MINIO_BUCKET") ?: "maple-expectation"
        testPrefix = "minio-it-${UUID.randomUUID()}/"

        s3 = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .serviceConfiguration(
                software.amazon.awssdk.services.s3.S3Configuration.builder()
                    .pathStyleAccessEnabled(true).build(),
            )
            .build()
        s3Async = S3AsyncClient.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .serviceConfiguration(
                software.amazon.awssdk.services.s3.S3Configuration.builder()
                    .pathStyleAccessEnabled(true).build(),
            )
            .multipartEnabled(true)
            .build()
        transferManager = S3TransferManager.builder()
            .s3Client(s3Async)
            .build()
        streamReaderExecutor = Executors.newVirtualThreadPerTaskExecutor()

        // Ensure bucket exists (idempotent)
        runCatching {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        }

        val props = MinioProperties(
            endpoint = endpoint,
            region = region,
            accessKey = accessKey,
            bucket = bucket,
            pathStyleAccess = true,
        )
        storage = MinioObjectStorage(
            props,
            s3,
            s3Async,
            transferManager,
            streamReaderExecutor,
            meterRegistry = null,
        )
    }

    @AfterAll
    fun tearDown() {
        if (::storage.isInitialized && ::testPrefix.isInitialized) {
            val cleanup = runCatching { storage.deleteByPrefix(testPrefix) }
            if (::transferManager.isInitialized) transferManager.close()
            if (::s3Async.isInitialized) s3Async.close()
            if (::s3.isInitialized) s3.close()
            if (::streamReaderExecutor.isInitialized) streamReaderExecutor.close()
            cleanup.getOrThrow()
        }
    }

    private fun testKey(name: String): String = "$testPrefix$name"

    override fun contractStorage(): ConditionalObjectStorage = storage

    override fun contractKey(relative: String): String = testKey("contract/$relative")

    @Test
    fun `failed async upload leaves caller file untouched`() {
        val source = Files.createTempFile("caller-owned-minio-failure-", ".bin")
        Files.writeString(source, "survives")
        source.toFile().deleteOnExit()
        val missingBucketStorage = MinioObjectStorage(
            MinioProperties(
                endpoint = endpoint,
                region = System.getenv("MINIO_REGION") ?: "us-east-1",
                accessKey = accessKey,
                bucket = "missing-${UUID.randomUUID()}",
                pathStyleAccess = true,
            ),
            s3,
            s3Async,
            transferManager,
            streamReaderExecutor,
            meterRegistry = null,
        )

        val upload = missingBucketStorage.putFileAsync(testKey("failed/file.bin"), source)

        await().atMost(Duration.ofMinutes(1)).until(upload::isDone)
        assertThat(upload).isCompletedExceptionally
        assertThat(Files.readString(source)).isEqualTo("survives")
        Files.delete(source)
    }

    @Test
    fun `put and get round-trip returns identical bytes`() {
        val data = "hello world".toByteArray()
        storage.put(testKey("file.txt"), data)
        val read = storage.get(testKey("file.txt"))
        assertThat(read).isEqualTo(data)
    }

    @Test
    fun `put returns PutResult with ETag checksum`() {
        val data = "test data".toByteArray()
        val result = storage.put(testKey("etag-test.txt"), data)
        assertThat(result.checksum).isNotNull
        assertThat(result.checksum).isNotEmpty
        assertThat(result.size).isEqualTo(data.size.toLong())
    }

    @Test
    fun `exists returns true after put, false for missing key`() {
        storage.put(testKey("present.txt"), "data".toByteArray())
        assertThat(storage.exists(testKey("present.txt"))).isTrue
        assertThat(storage.exists(testKey("missing-${UUID.randomUUID()}.txt"))).isFalse
    }

    @Test
    fun `get on missing key throws NoSuchKeyException`() {
        org.junit.jupiter.api.assertThrows<software.amazon.awssdk.services.s3.model.NoSuchKeyException> {
            storage.get(testKey("missing-${UUID.randomUUID()}.txt"))
        }
    }

    @Test
    fun `delete on missing key is no-op`() {
        // Should not throw
        storage.delete(testKey("missing-${UUID.randomUUID()}.txt"))
    }

    @Test
    fun `listByPrefix returns nested objects`() {
        storage.put(testKey("nested/a.txt"), "1".toByteArray())
        storage.put(testKey("nested/sub/b.txt"), "2".toByteArray())
        val keys = storage.listByPrefix(testKey("nested/")).map { it.key }
        assertThat(keys).contains(
            testKey("nested/a.txt"),
            testKey("nested/sub/b.txt"),
        )
    }

    @Test
    fun `listByPrefix returns empty list for non-existent prefix`() {
        val result = storage.listByPrefix(testKey("nonexistent-${UUID.randomUUID()}/"))
        assertThat(result).isEmpty()
    }

    @Test
    fun `deleteByPrefix removes all matches and returns byte count`() {
        storage.put(testKey("cleanup/a.txt"), "12345".toByteArray()) // 5 bytes
        storage.put(testKey("cleanup/b.txt"), "678".toByteArray()) // 3 bytes
        val deleted = storage.deleteByPrefix(testKey("cleanup/"))
        assertThat(deleted).isEqualTo(8L)
        assertThat(storage.exists(testKey("cleanup/a.txt"))).isFalse
    }

    @Test
    fun `getLastModified returns null for missing key`() {
        val result = storage.getLastModified(testKey("missing-${UUID.randomUUID()}.txt"))
        assertThat(result).isNull()
    }

    @Test
    fun `getLastModified returns Instant for existing key`() {
        storage.put(testKey("mod.txt"), "data".toByteArray())
        val result = storage.getLastModified(testKey("mod.txt"))
        assertThat(result).isNotNull
    }

    @Test
    fun `calculatePrefixSize matches sum of object sizes`() {
        storage.put(testKey("size/a.txt"), "12345".toByteArray())
        storage.put(testKey("size/b.txt"), "678".toByteArray())
        assertThat(storage.calculatePrefixSize(testKey("size/"))).isEqualTo(8L)
    }

    private fun requiredEnvironment(name: String): String = requireNotNull(System.getenv(name)) {
        "$name is required when INTEGRATION_MINIO=true"
    }.also { value -> require(value.isNotBlank()) { "$name must not be blank" } }
}
