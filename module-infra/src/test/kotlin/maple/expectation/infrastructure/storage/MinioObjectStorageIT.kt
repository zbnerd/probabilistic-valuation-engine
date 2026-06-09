package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.net.URI
import java.util.UUID

/**
 * Integration test for MinioObjectStorage. Runs only when INTEGRATION_MINIO=true.
 * Requires a running MinIO at MINIO_ENDPOINT (default http://localhost:9000) with
 * valid MINIO_ACCESS_KEY/MINIO_SECRET_KEY.
 */
@EnabledIfEnvironmentVariable(named = "INTEGRATION_MINIO", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioObjectStorageIT {

    private lateinit var s3: S3Client
    private lateinit var bucket: String
    private lateinit var testPrefix: String
    private lateinit var storage: MinioObjectStorage

    @BeforeAll
    fun setUp() {
        val endpoint = System.getenv("MINIO_ENDPOINT") ?: "http://localhost:9000"
        val accessKey = System.getenv("MINIO_ACCESS_KEY") ?: "maple"
        val secretKey = System.getenv("MINIO_SECRET_KEY") ?: "changeme"
        val region = System.getenv("MINIO_REGION") ?: "us-east-1"
        bucket = System.getenv("MINIO_BUCKET") ?: "maple-expectation"
        testPrefix = "minio-it-${UUID.randomUUID()}/"

        s3 = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .serviceConfiguration(
                software.amazon.awssdk.services.s3.S3Configuration.builder()
                    .pathStyleAccessEnabled(true).build()
            )
            .build()

        // Ensure bucket exists (idempotent)
        runCatching {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        }

        val props = MinioProperties(
            endpoint = endpoint,
            region = region,
            accessKey = accessKey,
            secretKey = secretKey,
            bucket = bucket,
            pathStyleAccess = true,
        )
        storage = MinioObjectStorage(props, s3, meterRegistry = null)
    }

    @AfterAll
    fun tearDown() {
        if (::s3.isInitialized && ::testPrefix.isInitialized) {
            val list = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucket).prefix(testPrefix).build()
            )
            list.contents().forEach { obj ->
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(obj.key()).build())
            }
        }
    }

    private fun testKey(name: String): String = "$testPrefix$name"

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
        storage.put(testKey("cleanup/a.txt"), "12345".toByteArray())  // 5 bytes
        storage.put(testKey("cleanup/b.txt"), "678".toByteArray())    // 3 bytes
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
}
