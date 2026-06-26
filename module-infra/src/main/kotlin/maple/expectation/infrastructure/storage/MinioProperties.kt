package maple.expectation.infrastructure.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the MinIO/S3 backend. Bound from `storage.minio.*` properties.
 * Required fields (no default): endpoint, accessKey, bucket.
 *
 * The SA secret key is intentionally NOT bound here. It is read directly from
 * the docker secret file (MINIO_SECRET_KEY_FILE) by [StorageConfig] so the
 * cleartext key never lives in an env_file or process env var. The compose
 * overlay mounts /run/secrets/sa-<module> per service.
 */
@ConfigurationProperties("storage.minio")
data class MinioProperties(
    val endpoint: String,
    val region: String = "us-east-1",
    val accessKey: String,
    val bucket: String,
    val pathStyleAccess: Boolean = true,
)
