package maple.pipeline.artifact.config

import maple.pipeline.artifact.storage.MinioProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest

@Component
@ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
class ArtifactStorageHealthIndicator(
    private val properties: MinioProperties,
    private val s3Client: S3Client,
) : HealthIndicator {
    override fun health(): Health = runCatching {
        s3Client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket).build())
        Health.up()
            .withDetail("bucket", properties.bucket)
            .withDetail("endpoint", properties.endpoint)
            .build()
    }.getOrElse { failure ->
        log.warn("[MinIO] health check failed: {}", failure.message)
        Health.down(failure)
            .withDetail("bucket", properties.bucket)
            .withDetail("endpoint", properties.endpoint)
            .build()
    }

    private companion object {
        val log = LoggerFactory.getLogger(ArtifactStorageHealthIndicator::class.java)
    }
}
