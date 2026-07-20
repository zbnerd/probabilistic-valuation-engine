package maple.pipeline.artifact.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/** Configuration for the MinIO/S3 backend, bound from `storage.minio.*`. */
@ConfigurationProperties("storage.minio")
data class MinioProperties(
    val endpoint: String,
    val region: String = "us-east-1",
    val accessKey: String,
    val bucket: String,
    val pathStyleAccess: Boolean = true,
)
