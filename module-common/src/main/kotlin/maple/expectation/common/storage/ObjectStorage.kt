package maple.expectation.common.storage

import java.io.InputStream
import java.time.Instant

/**
 * Unified object storage abstraction. Replaces the three local filesystem port
 * interfaces (SnapshotObjectStore, ExternalApiArtifactStorePort, calculator's
 * local ObjectStorage) plus direct Paths.get() access in synchronizer readers.
 *
 * Implementations: LocalFsObjectStorage (module-infra), MinioObjectStorage (module-infra).
 * Selected at boot via storage.backend=local|minio property.
 */
interface ObjectStorage {
    /** Put data. Returns PutResult with key, size, and checksum (SHA-256 hex for Local, S3 ETag for MinIO). */
    fun put(key: String, data: ByteArray): PutResult

    /** Put data from a stream. Caller is responsible for closing `input`. */
    fun putStream(key: String, input: InputStream): PutResult

    /** Get object as bytes. Throws if key not found. */
    fun get(key: String): ByteArray

    /** Get object as InputStream. Caller is responsible for closing the stream. Throws if key not found. */
    fun getStream(key: String): InputStream

    /** Delete object. No-op if key not found. */
    fun delete(key: String)

    /** True if key exists. */
    fun exists(key: String): Boolean

    /** List all objects under prefix (eager). Returns empty list if prefix has no objects. */
    fun listByPrefix(prefix: String): List<ObjectInfo>

    /** Delete all objects under prefix. Returns total bytes deleted. */
    fun deleteByPrefix(prefix: String): Long

    /** Sum of object sizes under prefix. Returns 0 if prefix empty. */
    fun calculatePrefixSize(prefix: String): Long

    /** Last-modified timestamp. Null if key not found. */
    fun getLastModified(key: String): Instant?
}

data class ObjectInfo(
    val key: String,
    val size: Long,
    val lastModified: Instant,
    /** S3 ETag (MD5 for single-part, composite for multipart). Null for Local. */
    val etag: String? = null,
)

data class PutResult(
    val key: String,
    val size: Long,
    /**
     * Checksum. SHA-256 hex for Local; S3 ETag for MinIO.
     * Callers must NOT assume algorithm. Use only for debug/metrics.
     */
    val checksum: String?,
)
