package maple.expectation.infrastructure.storage

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.transfer.s3.S3TransferManager
import software.amazon.awssdk.transfer.s3.model.Upload
import software.amazon.awssdk.transfer.s3.model.UploadRequest
import jakarta.annotation.PostConstruct
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MinIO/S3 implementation of [ObjectStorage]. Used when `storage.backend=minio`.
 * Validates the bucket in [validateBucket] (PostConstruct) — translates SDK errors
 * to IllegalStateException via runCatching (project policy: avoid raw try-catch).
 *
 * Retry: SDK built-in RetryPolicy.defaultRetryPolicy (configured in StorageConfig.s3Client).
 * S3Client is injected as a Spring bean.
 */
class MinioObjectStorage(
    private val props: MinioProperties,
    private val s3: S3Client,
    private val s3Async: S3AsyncClient,
    private val transferManager: S3TransferManager,
    @Autowired(required = false)
    private val meterRegistry: MeterRegistry?,
) : ObjectStorage {

    private val log = LoggerFactory.getLogger(MinioObjectStorage::class.java)

    /**
     * Executor passed to [AsyncRequestBody.fromInputStream]. The SDK requires
     * a non-null executor (`AsyncRequestBodyFromInputStreamConfiguration` ctor
     * rejects null). One shared virtual-thread executor handles all concurrent
     * `putStreamMultipart` stream reads — the work is blocking I/O against
     * the caller's InputStream (pipe, socket, file).
     */
    private val streamReadExecutor: ExecutorService =
        Executors.newVirtualThreadPerTaskExecutor()

    @PostConstruct
    fun validateBucket() {
        runCatching { s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket).build()) }
            .onFailure { e ->
                val message = when (e) {
                    is S3Exception -> "MinIO bucket '${props.bucket}' unreachable at ${props.endpoint} (status=${e.statusCode()}): ${e.message}"
                    is SdkClientException -> "MinIO endpoint '${props.endpoint}' unreachable: ${e.message}"
                    else -> "MinIO bucket validation failed: ${e.message}"
                }
                throw IllegalStateException(message, e)
            }
        log.info("[MinIO] bucket validated: bucket={}, endpoint={}", props.bucket, props.endpoint)
    }

    override fun put(key: String, data: ByteArray): PutResult {
        val resp = s3.putObject(
            PutObjectRequest.builder()
                .bucket(props.bucket)
                .key(key)
                .contentLength(data.size.toLong())
                .contentType("application/octet-stream")
                .build(),
            RequestBody.fromBytes(data),
        )
        return PutResult(key, data.size.toLong(), resp.eTag())
    }

    override fun putStream(key: String, input: java.io.InputStream): PutResult {
        // In-memory buffering — no temp file, no double-spool.
        //
        // Why not RequestBody.fromInputStream(input, -1L): sync S3Client.putObject
        // marshals Content-Length from RequestBody.contentLength(); the marshaller
        // throws IAE("Content-length must not be negative") for unknown length.
        // Chunked transfer (which allows length=-1) is async-only (S3AsyncClient +
        // AwsChunkedEncodingInputStream) and is not exposed through sync putObject.
        // Verified by attempt: every chunk failed with "Content-length must not be
        // negative", calculator_chunks_failed_total=1133 in <90s after the change.
        //
        // Why not temp file: Files.createTempFile + Files.copy + s3.putObject +
        // Files.deleteIfExists = 4 round-trips per chunk (disk read + disk write +
        // network read from disk + disk cleanup). 4 concurrent chunks contended
        // for /tmp I/O and stalled the writer.
        //
        // Current design: drain stream into a ByteArray, then use
        // RequestBody.fromByteArray with KNOWN length. Single network PUT, zero
        // disk I/O. Callers in this codebase already buffer the chunk in memory
        // (CalculationResultWriter builds ByteArrayOutputStream, then wraps in
        // ByteArrayInputStream to call putStream), so the buffer exists either way.
        // For callers with truly large streams, prefer putFileAsync (transfer
        // manager multipart, no memory pressure).
        val bytes = input.readBytes()
        val resp = s3.putObject(
            PutObjectRequest.builder()
                .bucket(props.bucket)
                .key(key)
                .contentLength(bytes.size.toLong())
                .contentType("application/octet-stream")
                .build(),
            RequestBody.fromBytes(bytes),
        )
        return PutResult(key, bytes.size.toLong(), resp.eTag())
    }

    override fun putFile(key: String, path: java.nio.file.Path): PutResult {
        // Stream the caller's file directly via the AWS SDK — the SDK reads
        // from the Path in 8MB chunks and (for objects >= 5MB) uses multipart
        // upload automatically. No intermediate spool, no extra disk write.
        // Saves ~128MB of disk I/O per chunk versus putStream on a 128MB
        // uncompressed chunk that the writer already has on disk.
        require(Files.exists(path)) { "putFile source does not exist: $path" }
        val size = Files.size(path)
        val resp = s3.putObject(
            PutObjectRequest.builder()
                .bucket(props.bucket)
                .key(key)
                .contentLength(size)
                .contentType("application/octet-stream")
                .build(),
            path,
        )
        return PutResult(key, size, resp.eTag())
    }

    override fun putStreamMultipart(
        key: String,
        input: java.io.InputStream,
    ): CompletableFuture<PutResult> {
        // Async chunked transfer: S3AsyncClient.putObject with
        // AsyncRequestBody.fromInputStream(input, contentLength=-1L)
        // tells the SDK to send chunks without knowing the total
        // length. The SDK internally wraps the InputStream in
        // SdkChunkedEncodingInputStream, sends 5MB chunks via
        // multipart, and tracks checksums per chunk.
        //
        // Why not sync putObject: the sync S3Client.putObject marshals
        // Content-Length from RequestBody.contentLength() and throws
        // IAE("Content-length must not be negative") for unknown length
        // (see putStream() below for the full history of this attempt).
        //
        // Retry: SDK built-in RetryPolicy.defaultRetryPolicy (3 retries,
        // configured in StorageConfig.s3AsyncClient).
        val req = PutObjectRequest.builder()
            .bucket(props.bucket)
            .key(key)
            .contentType("application/octet-stream")
            .build()

        val body = AsyncRequestBody.fromInputStream { b ->
            // SDK requires:
            // - executor: AsyncRequestBodyFromInputStreamConfiguration ctor rejects null
            //   (`Validate.paramNotNull(executor, "executor")`). The SDK reads the
            //   InputStream on a background thread (blocking I/O against a pipe or
            //   socket). See [streamReadExecutor].
            // - contentLength: not -1. `isNotNegativeOrNull` throws IAE for negative
            //   values. We pass null (i.e. unset) to signal "unknown length" — the
            //   SDK then uses chunked transfer encoding via
            //   AwsChunkedEncodingInputStream (5MB parts, per-chunk checksums).
            //
            // Verified: SDK 2.28.16 (in module-infra bootJar) throws
            // `IllegalArgumentException: contentLength must not be negative`
            // for `b.contentLength(-1L)`. Throws NPE for missing executor.
            // Both throws happen at build() time, not at runtime.
            b.inputStream(input).executor(streamReadExecutor)
        }

        return s3Async.putObject(req, body)
            .handle { resp, err ->
                if (err != null) {
                    throw RuntimeException(
                        "putStreamMultipart failed for key=$key",
                        err,
                    )
                }
                // Size is unknown with chunked transfer (-1L).
                PutResult(key, -1L, resp.eTag())
            }
    }

    override fun putFileAsync(key: String, path: java.nio.file.Path): CompletableFuture<PutResult> {
        // Use S3TransferManager for parallel multipart upload (5MB parts by
        // default). On a 128MB chunk, this is ~5-10x faster than the sync
        // single-threaded s3.putObject used in putFile() above — and the
        // writer doesn't block on the upload at all, so subsequent chunks
        // can start ingesting immediately while this one streams to MinIO.
        //
        // TransferManager's default thread pool (50 threads) handles many
        // concurrent uploads; backpressure comes from the bounded queue
        // used by ChunkFileManager.awaitAllUploads() at sink close.
        require(Files.exists(path)) { "putFileAsync source does not exist: $path" }
        val size = Files.size(path)
        val putObjectRequest = PutObjectRequest.builder()
            .bucket(props.bucket)
            .key(key)
            .contentLength(size)
            .contentType("application/octet-stream")
            .build()
        val uploadRequest = UploadRequest.builder()
            .putObjectRequest(putObjectRequest)
            .requestBody(AsyncRequestBody.fromFile(path))
            .build()
        val upload: Upload = transferManager.upload(uploadRequest)
        return upload.completionFuture().thenApply { completed ->
            // CompletedUpload.response() returns the underlying PutObjectResponse
            // (with multipart-upload, this is CompleteMultipartUploadResponse).
            // Both expose eTag().
            PutResult(key, size, completed.response().eTag())
        }
    }

    override fun get(key: String): ByteArray =
        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(props.bucket).key(key).build())
            .asByteArray()

    override fun getStream(key: String): java.io.InputStream =
        s3.getObject(GetObjectRequest.builder().bucket(props.bucket).key(key).build())

    override fun delete(key: String) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(props.bucket).key(key).build())
    }

    override fun exists(key: String): Boolean = try {
        s3.headObject(HeadObjectRequest.builder().bucket(props.bucket).key(key).build())
        true
    } catch (e: NoSuchKeyException) {
        false
    }

    override fun listByPrefix(prefix: String): List<ObjectInfo> {
        // Paginate via continuationToken. S3 ListObjectsV2 caps each page at
        // 1000 keys by default; without the loop we silently dropped any
        // keys past the first page. Module-cleanup's RunCleanupService
        // extracts runIds from these keys; truncated listings made the
        // cleanup return "no runs to delete" even when dozens of old runs
        // were eligible (e.g., 76 runs, only the first 2 runIds visible).
        val results = mutableListOf<ObjectInfo>()
        var continuation: String? = null
        do {
            val req = ListObjectsV2Request.builder()
                .bucket(props.bucket).prefix(prefix)
                .continuationToken(continuation)
                .build()
            val resp = s3.listObjectsV2(req)
            resp.contents().forEach { obj ->
                results.add(
                    ObjectInfo(
                        key = obj.key(),
                        size = obj.size(),
                        lastModified = obj.lastModified(),
                        etag = obj.eTag(),
                    )
                )
            }
            continuation = resp.nextContinuationToken()
        } while (continuation != null)
        return results
    }

    override fun deleteByPrefix(prefix: String): Long {
        var totalBytes = 0L
        var continuation: String? = null
        do {
            val listReq = ListObjectsV2Request.builder()
                .bucket(props.bucket).prefix(prefix)
                .continuationToken(continuation)
                .build()
            val resp = s3.listObjectsV2(listReq)
            if (resp.contents().isNotEmpty()) {
                totalBytes += resp.contents().sumOf { it.size() }
                s3.deleteObjects(
                    DeleteObjectsRequest.builder()
                        .bucket(props.bucket)
                        .delete { d ->
                            d.objects(
                                resp.contents().map { o ->
                                    ObjectIdentifier.builder().key(o.key()).build()
                                }
                            )
                        }
                        .build()
                )
            }
            continuation = resp.nextContinuationToken()
        } while (continuation != null)
        return totalBytes
    }

    override fun calculatePrefixSize(prefix: String): Long {
        var total = 0L
        var continuation: String? = null
        do {
            val resp = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                    .bucket(props.bucket).prefix(prefix)
                    .continuationToken(continuation)
                    .build()
            )
            total += resp.contents().sumOf { it.size() }
            continuation = resp.nextContinuationToken()
        } while (continuation != null)
        return total
    }

    override fun getLastModified(key: String): Instant? = try {
        s3.headObject(HeadObjectRequest.builder().bucket(props.bucket).key(key).build())
            .lastModified()
    } catch (e: NoSuchKeyException) {
        null
    }
}
