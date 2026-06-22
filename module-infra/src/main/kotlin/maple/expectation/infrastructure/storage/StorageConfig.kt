package maple.expectation.infrastructure.storage

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.common.storage.ObjectStorage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.transfer.s3.S3TransferManager
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Selects the active [ObjectStorage] implementation based on `storage.backend`.
 * Default is `local`. Setting `storage.backend=minio` switches to [MinioObjectStorage].
 *
 * The MinIO SA secret key is read directly from the docker secret file at
 * MINIO_SECRET_KEY_FILE (default `/run/secrets/sa-<module>`) instead of an
 * env var, so the cleartext key is not exposed in `docker inspect` output
 * or any process environment dump.
 *
 * S3Client is exposed as a Spring bean so it can be shared by [MinioObjectStorage],
 * [MinioHealthIndicator], and any other future consumers (e.g., metrics, backups).
 */
@Configuration
@EnableConfigurationProperties(MinioProperties::class)
class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "local", matchIfMissing = true)
    fun localObjectStorage(
        @Value("\${storage.local.base-path:../data}") basePath: String,
        uploadExecutor: java.util.concurrent.Executor,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ): ObjectStorage = LocalFsObjectStorage(basePath, uploadExecutor, meterRegistry)

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun s3Client(
        props: MinioProperties,
        @Value("\${MINIO_SECRET_KEY_FILE:/run/secrets/sa-\${MODULE_NAME:external-api}}") secretFile: String,
    ): S3Client {
        val secretKey = readSecretKey(secretFile)
        return S3Client.builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey, secretKey)
                )
            )
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(props.pathStyleAccess).build()
            )
            .httpClient(ApacheHttpClient.builder().build())
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .retryPolicy(RetryPolicy.defaultRetryPolicy())
                    .build()
            )
            .build()
    }

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun s3AsyncClient(
        props: MinioProperties,
        @Value("\${MINIO_SECRET_KEY_FILE:/run/secrets/sa-\${MODULE_NAME:external-api}}") secretFile: String,
    ): S3AsyncClient {
        val secretKey = readSecretKey(secretFile)
        // S3TransferManager requires an S3AsyncClient (its uploads
        // pipeline is built around the async client). We share the same
        // endpoint / credentials / path-style config as the sync S3Client
        // bean so both clients talk to the same MinIO instance.
        return S3AsyncClient.builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey, secretKey)
                )
            )
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(props.pathStyleAccess).build()
            )
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .retryPolicy(RetryPolicy.defaultRetryPolicy())
                    .build()
            )
            .build()
    }

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun s3TransferManager(s3AsyncClient: S3AsyncClient): S3TransferManager =
        // Default S3TransferManager uses a 50-thread executor for both
        // upload-part submissions and completions, which is plenty for our
        // 128MB chunk size. Part size defaults to 5MB → ~26 parts per
        // 128MB upload, parallelised by TransferManager.
        S3TransferManager.builder()
            .s3Client(s3AsyncClient)
            .build()

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun minioObjectStorage(
        props: MinioProperties,
        s3: S3Client,
        s3AsyncClient: S3AsyncClient,
        transferManager: S3TransferManager,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ): ObjectStorage = MinioObjectStorage(props, s3, s3AsyncClient, transferManager, meterRegistry)

    /**
     * Reads the SA secret key from the docker secret file.
     * Hard-fails (throws) if the file is missing or empty — this matches the
     * "fail fast" pattern of the previous entrypoint-wrapper.sh and prevents
     * silent S3 AccessDenied errors masking the real config bug.
     */
    private fun readSecretKey(secretFile: String): String {
        val path = Paths.get(secretFile)
        if (!Files.exists(path)) {
            throw IllegalStateException(
                "MINIO_SECRET_KEY_FILE not found at '$secretFile' — " +
                    "is docker/services/secrets/sa-<module>.key present and " +
                    "mounted via the secrets: overlay in docker-compose.services.yml?"
            )
        }
        val key = Files.readString(path).trim()
        if (key.isEmpty()) {
            throw IllegalStateException("MINIO_SECRET_KEY_FILE '$secretFile' is empty")
        }
        return key
    }
}