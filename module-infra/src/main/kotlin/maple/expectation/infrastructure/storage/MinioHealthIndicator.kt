package maple.expectation.infrastructure.storage

import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest

/**
 * Exposes MinIO bucket health at /actuator/health.
 * NOT used as a liveness gate (per spec §8.5 — boot-time fatal already validates
 * the bucket via @PostConstruct). This is for runtime observability only.
 */
@Component
@ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
class MinioHealthIndicator(
    private val props: MinioProperties,
    private val s3: S3Client,
) : HealthIndicator {

    private val log = LoggerFactory.getLogger(MinioHealthIndicator::class.java)

    override fun health(): Health = try {
        s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket).build())
        Health.up()
            .withDetail("bucket", props.bucket)
            .withDetail("endpoint", props.endpoint)
            .build()
    } catch (e: Exception) {
        log.warn("[MinIO] health check failed: {}", e.message)
        Health.down(e)
            .withDetail("bucket", props.bucket)
            .withDetail("endpoint", props.endpoint)
            .build()
    }
}
