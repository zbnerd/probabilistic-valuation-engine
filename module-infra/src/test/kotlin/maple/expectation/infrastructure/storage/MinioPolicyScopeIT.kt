package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI

/**
 * Per-SA policy scope test (4 SAs).
 *
 * Seeds the bucket with one object per prefix (using root credentials),
 * then attempts GetObject on each in-scope and out-of-scope key with each SA.
 * Expects 200 for in-scope, S3Exception (403) for out-of-scope.
 *
 * Gated on INTEGRATION_MINIO=true.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "INTEGRATION_MINIO", matches = "true")
class MinioPolicyScopeIT {

    private val endpoint = System.getenv("MINIO_ENDPOINT") ?: "http://localhost:9000"
    private val rootKey = System.getenv("MINIO_ACCESS_KEY") ?: error("MINIO_ACCESS_KEY required")
    private val rootSecret = System.getenv("MINIO_SECRET_KEY") ?: error("MINIO_SECRET_KEY required")
    private val bucket = "maple-expectation"

    private val rootClient: S3Client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(rootKey, rootSecret)))
        .serviceConfiguration(
            software.amazon.awssdk.services.s3.S3Configuration.builder()
                .pathStyleAccessEnabled(true).build()
        )
        .build()

    private fun saClient(saName: String, saSecret: String): S3Client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(saName, saSecret)))
        .serviceConfiguration(
            software.amazon.awssdk.services.s3.S3Configuration.builder()
                .pathStyleAccessEnabled(true).build()
        )
        .build()

    private fun putSeed(key: String) {
        rootClient.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            RequestBody.fromBytes("seed".toByteArray())
        )
    }

    private fun saSecret(envName: String): String =
        System.getenv(envName) ?: error("$envName required for this IT")

    @Test
    fun `ext-api can read runs snapshots ocid-mapping - denied on calculator prefix`() {
        putSeed("runs/20260615-120000-000001/_SUCCESS")
        putSeed("snapshots/2026/06/15/job.gz")
        putSeed("ocid-mapping/2026-06-15.jsonl.gz")
        putSeed("calculator/runs/20260615-120000-000001/result.jsonl.gz")

        val client = saClient("ext-api", saSecret("SA_EXT_API_SECRET_KEY"))

        // in-scope
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("runs/20260615-120000-000001/_SUCCESS").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("snapshots/2026/06/15/job.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("ocid-mapping/2026-06-15.jsonl.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")

        // out-of-scope
        try {
            client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("calculator/runs/20260615-120000-000001/result.jsonl.gz").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected 403 on out-of-scope key for ext-api")
        } catch (e: S3Exception) {
            assertThat(e.statusCode()).isEqualTo(403)
        }
    }

    @Test
    fun `calculator can read runs and data snapshots - can write calculator runs - denied on ocid-mapping`() {
        putSeed("runs/20260615-120000-000002/chunk.jsonl.gz")
        putSeed("data/snapshots/2026/06/15/job.gz")
        putSeed("ocid-mapping/2026-06-15.jsonl.gz")

        val client = saClient("calculator", saSecret("SA_CALCULATOR_SECRET_KEY"))

        // in-scope read
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("runs/20260615-120000-000002/chunk.jsonl.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("data/snapshots/2026/06/15/job.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")

        // in-scope write
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key("calculator/runs/20260615-120000-000002/result.jsonl.gz").build(),
            RequestBody.fromBytes("calc".toByteArray())
        )
        assertThat(rootClient.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("calculator/runs/20260615-120000-000002/result.jsonl.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("calc")

        // out-of-scope: ocid-mapping
        try {
            client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("ocid-mapping/2026-06-15.jsonl.gz").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected 403 on ocid-mapping for calculator")
        } catch (e: S3Exception) {
            assertThat(e.statusCode()).isEqualTo(403)
        }
    }

    @Test
    fun `synchronizer can read runs calculator and ocid-mapping and cannot write ocid-mapping`() {
        putSeed("runs/20260615-120000-000004/ocid-lookup/manifest.jsonl")
        putSeed("calculator/runs/20260615-120000-000004/result.jsonl.gz")
        putSeed("ocid-mapping/2026-06-15.jsonl.gz")
        putSeed("ocid-mapping/other-prefix/test.gz")  // should be allowed (ocid-mapping/* matches)

        val client = saClient("synchronizer", saSecret("SA_SYNCHRONIZER_SECRET_KEY"))

        // in-scope: read all 3 expected prefixes
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("runs/20260615-120000-000004/ocid-lookup/manifest.jsonl").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("calculator/runs/20260615-120000-000004/result.jsonl.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("ocid-mapping/2026-06-15.jsonl.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")

        // in-scope: cannot write to ocid-mapping (ext-api is the sole writer)
        try {
            client.putObject(
                PutObjectRequest.builder().bucket(bucket).key("ocid-mapping/sync-attempt.jsonl.gz").build(),
                RequestBody.fromBytes("should-fail".toByteArray())
            )
            org.junit.jupiter.api.Assertions.fail("expected 403 on synchronizer writing ocid-mapping")
        } catch (e: S3Exception) {
            assertThat(e.statusCode()).isEqualTo(403)
        }
    }

    @Test
    fun `cleanup can delete runs prefix - denied on ocid-mapping and snapshots`() {
        putSeed("runs/20260615-120000-000003/chunk.jsonl.gz")
        putSeed("ocid-mapping/2026-06-15.json")
        putSeed("snapshots/2026/06/15/job.gz")

        val client = saClient("cleanup", saSecret("SA_CLEANUP_SECRET_KEY"))

        // in-scope: delete succeeds
        client.deleteObject(
            DeleteObjectRequest.builder().bucket(bucket).key("runs/20260615-120000-000003/chunk.jsonl.gz").build()
        )
        try {
            rootClient.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("runs/20260615-120000-000003/chunk.jsonl.gz").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected object to be deleted")
        } catch (_: NoSuchKeyException) { /* ok */ }

        // out-of-scope: ocid-mapping read 403
        try {
            client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("ocid-mapping/2026-06-15.json").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected 403 on ocid-mapping for cleanup")
        } catch (e: S3Exception) {
            assertThat(e.statusCode()).isEqualTo(403)
        }

        // out-of-scope: snapshots read 403
        try {
            client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("snapshots/2026/06/15/job.gz").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected 403 on snapshots for cleanup")
        } catch (e: S3Exception) {
            assertThat(e.statusCode()).isEqualTo(403)
        }
    }
}
