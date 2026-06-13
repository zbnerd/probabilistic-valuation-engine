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
    private val transferManager: S3TransferManager,
    @Autowired(required = false)
    private val meterRegistry: MeterRegistry?,
) : ObjectStorage {

    private val log = LoggerFactory.getLogger(MinioObjectStorage::class.java)

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
        val tempFile = Files.createTempFile("minio-put-", ".tmp")
        try {
            val bytes = input.use { Files.copy(it, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            val resp = s3.putObject(
                PutObjectRequest.builder()
                    .bucket(props.bucket)
                    .key(key)
                    .contentLength(bytes)
                    .build(),
                tempFile,
            )
            return PutResult(key, bytes, resp.eTag())
        } finally {
            Files.deleteIfExists(tempFile)
        }
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
        val req = ListObjectsV2Request.builder().bucket(props.bucket).prefix(prefix).build()
        return s3.listObjectsV2(req).contents().map { obj ->
            ObjectInfo(
                key = obj.key(),
                size = obj.size(),
                lastModified = obj.lastModified(),
                etag = obj.eTag(),
            )
        }
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
