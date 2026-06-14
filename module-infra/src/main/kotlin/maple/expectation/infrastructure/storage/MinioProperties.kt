package maple.expectation.infrastructure.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the MinIO/S3 backend. Bound from `storage.minio.*` properties.
 * Required fields (no default): endpoint, accessKey, secretKey, bucket.
 */
@ConfigurationProperties("storage.minio")
data class MinioProperties(
    val endpoint: String,
    val region: String = "us-east-1",
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    val pathStyleAccess: Boolean = true,
)
