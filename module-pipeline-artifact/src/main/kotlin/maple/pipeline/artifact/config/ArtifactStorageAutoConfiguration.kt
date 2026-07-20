package maple.pipeline.artifact.config

import io.micrometer.core.instrument.MeterRegistry
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import maple.pipeline.artifact.storage.ArtifactUploadResources
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import maple.pipeline.artifact.storage.LocalFsObjectStorage
import maple.pipeline.artifact.storage.MinioClientConfiguration
import maple.pipeline.artifact.storage.MinioObjectStorage
import maple.pipeline.artifact.storage.MinioProperties
import maple.pipeline.artifact.storage.MinioStorageResources
import maple.pipeline.artifact.storage.MinioStorageResourcesFactory
import maple.pipeline.artifact.write.ArtifactWriter
import maple.pipeline.artifact.write.DefaultArtifactWriter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MinioProperties::class)
class ArtifactStorageAutoConfiguration {
    @Bean(destroyMethod = "close")
    fun artifactUploadResources(
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): ArtifactUploadResources = ArtifactUploadResources(meterRegistry.ifAvailable)

    @Bean(name = ["artifactUploadExecutor"], destroyMethod = "")
    fun artifactUploadExecutor(resources: ArtifactUploadResources): ExecutorService = resources.executor

    @Bean
    fun artifactWriter(
        objectStorage: ConditionalObjectStorage,
        @Qualifier("artifactUploadExecutor") uploadExecutor: Executor,
    ): ArtifactWriter = DefaultArtifactWriter(objectStorage, uploadExecutor)

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "local", matchIfMissing = true)
    fun localObjectStorage(
        @Value("\${storage.local.base-path:../data}") basePath: String,
        @Qualifier("artifactUploadExecutor") uploadExecutor: Executor,
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): ConditionalObjectStorage = LocalFsObjectStorage(basePath, uploadExecutor, meterRegistry.ifAvailable)

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun minioStorageResources(
        properties: MinioProperties,
        @Value("\${MINIO_SECRET_KEY_FILE:/run/secrets/sa-\${MODULE_NAME:external-api}}") secretFile: String,
    ): MinioStorageResources {
        val credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(properties.accessKey, readSecretKey(secretFile)),
        )
        val serviceConfiguration = S3Configuration.builder()
            .pathStyleAccessEnabled(properties.pathStyleAccess)
            .build()
        return MinioStorageResourcesFactory().create(
            MinioClientConfiguration(
                endpoint = URI.create(properties.endpoint),
                region = Region.of(properties.region),
                credentialsProvider = credentials,
                serviceConfiguration = serviceConfiguration,
            ),
        )
    }

    @Bean(name = ["artifactStorageS3Client"], destroyMethod = "")
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun artifactStorageS3Client(resources: MinioStorageResources): S3Client = resources.syncClient

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun artifactStorageHealthIndicator(
        properties: MinioProperties,
        @Qualifier("artifactStorageS3Client") s3Client: S3Client,
    ): ArtifactStorageHealthIndicator = ArtifactStorageHealthIndicator(properties, s3Client)

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun minioObjectStorage(
        properties: MinioProperties,
        resources: MinioStorageResources,
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): ConditionalObjectStorage = MinioObjectStorage(
        properties,
        resources.syncClient,
        resources.asyncClient,
        resources.transferManager,
        resources.streamReaderExecutor,
        meterRegistry.ifAvailable,
    )

    private fun readSecretKey(secretFile: String): String {
        val path = Paths.get(secretFile)
        check(Files.exists(path)) {
            "MINIO_SECRET_KEY_FILE not found at '$secretFile' — " +
                "is docker/services/secrets/sa-<module>.key present and mounted?"
        }
        val secretKey = Files.readString(path).trim()
        check(secretKey.isNotEmpty()) { "MINIO_SECRET_KEY_FILE '$secretFile' is empty" }
        return secretKey
    }
}
