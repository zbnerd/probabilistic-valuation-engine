package maple.expectation.common.storage

import java.io.InputStream
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * Unified object storage abstraction. Replaces the deprecated per-module
 * filesystem port interfaces (SnapshotObjectStore, ExternalApiArtifactStorePort)
 * and the calculator's local ObjectStorage, plus direct Paths.get() access
 * in synchronizer readers.
 *
 * Implementations: LocalFsObjectStorage (module-infra), MinioObjectStorage (module-infra).
 * Selected at boot via storage.backend=local|minio property.
 */
interface ObjectStorage {
    /** Put data. Returns PutResult with key, size, and checksum (SHA-256 hex for Local, S3 ETag for MinIO). */
    fun put(key: String, data: ByteArray): PutResult

    /**
     * Put data from a stream. Caller is responsible for closing `input`.
     *
     * **Deprecated** since issue #1312. Buffers the full stream in heap
     * via `readBytes()` (see `MinioObjectStorage.putStream` and
     * `LocalFsObjectStorage.putStream` for the heap-drain paths),
     * defeating the purpose of streaming uploads for chunks > 1MB.
     * Use [putStreamMultipart] instead, which uses S3 chunked transfer
     * encoding (Minio) or temp-file + putFile (LocalFs) with bounded
     * heap.
     *
     * The remaining legacy caller is
     * `module-external-api/.../OcidLookupPhase.kt:118` which has the
     * same heap problem and should migrate to `putStreamMultipart` in a
     * separate follow-up issue.
     */
    @Deprecated(
        message = "Buffers full stream in heap. Use putStreamMultipart for chunks > 1MB.",
        replaceWith = ReplaceWith("putStreamMultipart"),
    )
    fun putStream(key: String, input: InputStream): PutResult

    /**
     * Put a pre-existing file at [path] under [key]. Avoids the double-spool
     * of [putStream] for callers that already have the bytes on disk (e.g.
     * [maple.externalapi.snapshot.GzipJsonlChunkWriter] which writes each
     * chunk to a temp file before upload). The caller relinquishes the file —
     * implementations move or upload it, and the caller MUST NOT delete the
     * file or write to it again after this call returns.
     *
     * Throws if [path] does not exist.
     */
    fun putFile(key: String, path: Path): PutResult

    /**
     * Async variant of [putFile] — returns immediately with a future that
     * completes when the upload finishes. Used by the writer hot path to
     * overlap multiple 128MB chunk uploads (each takes 5-10s on MinIO)
     * with subsequent record ingestion. The caller MUST treat [path] as
     * transferred to the storage backend (no delete, no rewrite) once
     * this method returns.
     *
     * Implementations:
     * - Minio: backed by [software.amazon.awssdk.transfer.s3.S3TransferManager]
     *   (parallel multipart, 5MB parts). The default TransferManager thread
     *   pool handles uploads in parallel.
     * - LocalFs: runs the sync [putFile] on a virtual-thread executor and
     *   returns the resulting future.
     *
     * On failure the future completes exceptionally; the impl does NOT
     * silently delete the source file.
     */
    fun putFileAsync(key: String, path: Path): CompletableFuture<PutResult>

    /**
     * Async streaming upload. Accepts an [InputStream] of arbitrary length
     * and uploads without buffering the full content in heap. The caller is
     * responsible for closing [input] only after the returned future
     * completes (success or failure).
     *
     * Implementations:
     * - Minio: [software.amazon.awssdk.services.s3.S3AsyncClient.putObject]
     *   with [software.amazon.awssdk.core.async.AsyncRequestBody.fromInputStream]
     *   and `contentLength = -1L` (chunked transfer encoding, no
     *   intermediate ByteArray drain).
     * - LocalFs: drain [input] to a temp file, then call [putFile] on a
     *   virtual-thread executor; delete the temp file on completion.
     *
     * On failure the future completes exceptionally with the underlying
     * cause. On success the future completes with a [PutResult] whose
     * `size` is the byte count actually uploaded (or -1L for chunked
     * transfer where size is unknown a priori).
     */
    fun putStreamMultipart(key: String, input: InputStream): CompletableFuture<PutResult>

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
