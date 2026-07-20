package maple.pipeline.artifact.storage

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.ArtifactPrefix
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
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
import software.amazon.awssdk.transfer.s3.model.UploadRequest

/** MinIO/S3 implementation selected by `storage.backend=minio`. */
class MinioObjectStorage(
    private val properties: MinioProperties,
    private val s3Client: S3Client,
    private val s3AsyncClient: S3AsyncClient,
    private val transferManager: S3TransferManager,
    private val streamReaderExecutor: ExecutorService,
    @Suppress("unused") private val meterRegistry: MeterRegistry?,
) : ConditionalObjectStorage {
    @PostConstruct
    fun validateBucket() {
        runCatching {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket).build())
        }.onFailure { failure ->
            val message = when (failure) {
                is S3Exception ->
                    "MinIO bucket '${properties.bucket}' unreachable at ${properties.endpoint} " +
                        "(status=${failure.statusCode()}): ${failure.message}"
                is SdkClientException -> "MinIO endpoint '${properties.endpoint}' unreachable: ${failure.message}"
                else -> "MinIO bucket validation failed: ${failure.message}"
            }
            throw IllegalStateException(message, failure)
        }
        log.info("[MinIO] bucket validated: bucket={}, endpoint={}", properties.bucket, properties.endpoint)
    }

    override fun put(key: String, data: ByteArray): PutResult {
        val response = s3Client.putObject(
            putRequest(key, data.size.toLong()),
            RequestBody.fromBytes(data),
        )
        return PutResult(key, data.size.toLong(), response.eTag())
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun putStream(key: String, input: InputStream): PutResult {
        val bytes = input.readBytes()
        val response = s3Client.putObject(
            putRequest(key, bytes.size.toLong()),
            RequestBody.fromBytes(bytes),
        )
        return PutResult(key, bytes.size.toLong(), response.eTag())
    }

    override fun putFile(key: String, path: Path): PutResult {
        require(Files.exists(path)) { "putFile source does not exist: $path" }
        val size = Files.size(path)
        val response = s3Client.putObject(putRequest(key, size), path)
        return PutResult(key, size, response.eTag())
    }

    override fun putFileAsync(key: String, path: Path): CompletableFuture<PutResult> {
        require(Files.exists(path)) { "putFileAsync source does not exist: $path" }
        val size = Files.size(path)
        val request = UploadRequest.builder()
            .putObjectRequest(putRequest(key, size))
            .requestBody(AsyncRequestBody.fromFile(path))
            .build()
        return transferManager.upload(request).completionFuture().thenApply { completed ->
            PutResult(key, size, completed.response().eTag())
        }
    }

    override fun putStreamMultipart(key: String, input: InputStream): CompletableFuture<PutResult> {
        val request = PutObjectRequest.builder()
            .bucket(properties.bucket)
            .key(key)
            .contentType(CONTENT_TYPE)
            .build()
        val body = AsyncRequestBody.fromInputStream { configuration ->
            configuration
                .inputStream(NonClosingInputStream(input))
                .executor(streamReaderExecutor)
        }
        return s3AsyncClient.putObject(request, body).thenApply { response ->
            PutResult(key, UNKNOWN_CONTENT_LENGTH, response.eTag())
        }
    }

    override fun putIfAbsent(key: String, data: ByteArray): CompletionStage<PutIfAbsentResult> {
        val snapshot = data.copyOf()
        val request = PutObjectRequest.builder()
            .bucket(properties.bucket)
            .key(key)
            .contentLength(snapshot.size.toLong())
            .contentType(CONTENT_TYPE)
            .ifNoneMatch("*")
            .build()
        return s3AsyncClient.putObject(request, AsyncRequestBody.fromBytes(snapshot))
            .handle { response, failure ->
                when {
                    failure == null -> CompletableFuture.completedFuture<PutIfAbsentResult>(
                        PutIfAbsentResult.Created(response.eTag()),
                    )
                    isPreconditionFailure(failure) -> readExisting(key)
                    else -> CompletableFuture.failedFuture(unwrapCompletion(failure))
                }
            }
            .thenCompose { stage -> stage }
    }

    override fun listPage(prefix: ArtifactPrefix, afterKey: ArtifactKey?, limit: Int): StorageObjectPage {
        validatePageRequest(prefix, afterKey, limit)
        val request = ListObjectsV2Request.builder()
            .bucket(properties.bucket)
            .prefix(prefix.value)
            .maxKeys(limit)
            .also { builder -> afterKey?.let { builder.startAfter(it.value) } }
            .build()
        val response = s3Client.listObjectsV2(request)
        val objects = response.contents().map { objectSummary ->
            ObjectInfo(
                key = objectSummary.key(),
                size = objectSummary.size(),
                lastModified = objectSummary.lastModified(),
                etag = objectSummary.eTag(),
            )
        }
        val next = if (response.isTruncated == true) {
            objects.lastOrNull()?.let { ArtifactKey.require(it.key) }
        } else {
            null
        }
        return StorageObjectPage(objects, next)
    }

    override fun get(key: String): ByteArray = s3Client.getObjectAsBytes(getRequest(key)).asByteArray()

    override fun getStream(key: String): InputStream = s3Client.getObject(getRequest(key))

    override fun delete(key: String) {
        s3Client.deleteObject(
            DeleteObjectRequest.builder().bucket(properties.bucket).key(key).build(),
        )
    }

    override fun exists(key: String): Boolean = runCatching {
        s3Client.headObject(headRequest(key))
        true
    }.getOrElse { failure ->
        if (isNotFound(failure)) false else throw unwrapCompletion(failure)
    }

    override fun listByPrefix(prefix: String): List<ObjectInfo> {
        val results = mutableListOf<ObjectInfo>()
        var continuationToken: String? = null
        do {
            val response = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                    .bucket(properties.bucket)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build(),
            )
            response.contents().forEach { objectSummary ->
                results.add(
                    ObjectInfo(
                        key = objectSummary.key(),
                        size = objectSummary.size(),
                        lastModified = objectSummary.lastModified(),
                        etag = objectSummary.eTag(),
                    ),
                )
            }
            continuationToken = response.nextContinuationToken()
        } while (continuationToken != null)
        return results.toList()
    }

    override fun deleteByPrefix(prefix: String): Long {
        var totalBytes = 0L
        var continuationToken: String? = null
        do {
            val response = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                    .bucket(properties.bucket)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build(),
            )
            if (response.contents().isNotEmpty()) {
                totalBytes += response.contents().sumOf { it.size() }
                s3Client.deleteObjects(
                    DeleteObjectsRequest.builder()
                        .bucket(properties.bucket)
                        .delete { delete ->
                            delete.objects(
                                response.contents().map { objectSummary ->
                                    ObjectIdentifier.builder().key(objectSummary.key()).build()
                                },
                            )
                        }
                        .build(),
                )
            }
            continuationToken = response.nextContinuationToken()
        } while (continuationToken != null)
        return totalBytes
    }

    override fun calculatePrefixSize(prefix: String): Long {
        var totalBytes = 0L
        var continuationToken: String? = null
        do {
            val response = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                    .bucket(properties.bucket)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build(),
            )
            totalBytes += response.contents().sumOf { it.size() }
            continuationToken = response.nextContinuationToken()
        } while (continuationToken != null)
        return totalBytes
    }

    override fun getLastModified(key: String): Instant? = runCatching { s3Client.headObject(headRequest(key)).lastModified() }
        .getOrElse { failure ->
            if (isNotFound(failure)) null else throw unwrapCompletion(failure)
        }

    private fun readExisting(key: String): CompletableFuture<PutIfAbsentResult> = s3AsyncClient.getObject(getRequest(key), AsyncResponseTransformer.toBytes())
        .thenApply { response ->
            PutIfAbsentResult.Existing(
                response.asByteArray(),
                response.response().eTag(),
            )
        }

    private fun putRequest(key: String, contentLength: Long): PutObjectRequest = PutObjectRequest.builder()
        .bucket(properties.bucket)
        .key(key)
        .contentLength(contentLength)
        .contentType(CONTENT_TYPE)
        .build()

    private fun getRequest(key: String): GetObjectRequest = GetObjectRequest.builder().bucket(properties.bucket).key(key).build()

    private fun headRequest(key: String): HeadObjectRequest = HeadObjectRequest.builder().bucket(properties.bucket).key(key).build()

    private fun isPreconditionFailure(failure: Throwable): Boolean = (unwrapCompletion(failure) as? S3Exception)?.statusCode() == PRECONDITION_FAILED

    private fun isNotFound(failure: Throwable): Boolean {
        val unwrapped = unwrapCompletion(failure)
        return unwrapped is NoSuchKeyException ||
            (unwrapped as? S3Exception)?.statusCode() == NOT_FOUND
    }

    private fun unwrapCompletion(failure: Throwable): Throwable {
        val cause = failure.cause
        return if ((failure is CompletionException || failure is ExecutionException) && cause != null) {
            unwrapCompletion(cause)
        } else {
            failure
        }
    }

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private companion object {
        const val CONTENT_TYPE = "application/octet-stream"
        const val UNKNOWN_CONTENT_LENGTH = -1L
        const val PRECONDITION_FAILED = 412
        const val NOT_FOUND = 404
        val log = LoggerFactory.getLogger(MinioObjectStorage::class.java)
    }
}
