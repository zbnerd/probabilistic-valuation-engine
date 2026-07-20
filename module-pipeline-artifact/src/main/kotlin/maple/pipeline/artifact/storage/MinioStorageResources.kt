package maple.pipeline.artifact.storage

import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.transfer.s3.S3TransferManager

internal data class MinioClientConfiguration(
    val endpoint: URI,
    val region: Region,
    val credentialsProvider: AwsCredentialsProvider,
    val serviceConfiguration: S3Configuration,
)

internal interface MinioStorageResourceFactory {
    fun createSyncClient(configuration: MinioClientConfiguration): S3Client

    fun createAsyncClient(configuration: MinioClientConfiguration): S3AsyncClient

    fun createTransferManager(asyncClient: S3AsyncClient): S3TransferManager

    fun createStreamReaderExecutor(): ExecutorService
}

internal class MinioStorageResourcesFactory(
    private val resourceFactory: MinioStorageResourceFactory = SdkMinioStorageResourceFactory,
) {
    fun create(configuration: MinioClientConfiguration): MinioStorageResources {
        var syncClient: S3Client? = null
        var asyncClient: S3AsyncClient? = null
        var transferManager: S3TransferManager? = null
        var streamReaderExecutor: ExecutorService? = null
        return runCatching {
            val acquiredSyncClient = resourceFactory.createSyncClient(configuration)
                .also { syncClient = it }
            val acquiredAsyncClient = resourceFactory.createAsyncClient(configuration)
                .also { asyncClient = it }
            val acquiredTransferManager = resourceFactory.createTransferManager(acquiredAsyncClient)
                .also { transferManager = it }
            val acquiredStreamReaderExecutor = resourceFactory.createStreamReaderExecutor()
                .also { streamReaderExecutor = it }
            MinioStorageResources(
                acquiredTransferManager,
                acquiredAsyncClient,
                acquiredSyncClient,
                acquiredStreamReaderExecutor,
            )
        }.getOrElse { constructionFailure ->
            closeMinioResources(
                transferManager,
                asyncClient,
                syncClient,
                streamReaderExecutor,
            ).forEach(constructionFailure::addSuppressed)
            throw constructionFailure
        }
    }
}

private object SdkMinioStorageResourceFactory : MinioStorageResourceFactory {
    override fun createSyncClient(configuration: MinioClientConfiguration): S3Client = S3Client.builder()
        .endpointOverride(configuration.endpoint)
        .region(configuration.region)
        .credentialsProvider(configuration.credentialsProvider)
        .serviceConfiguration(configuration.serviceConfiguration)
        .httpClientBuilder(ApacheHttpClient.builder())
        .build()

    override fun createAsyncClient(configuration: MinioClientConfiguration): S3AsyncClient = S3AsyncClient.builder()
        .endpointOverride(configuration.endpoint)
        .region(configuration.region)
        .credentialsProvider(configuration.credentialsProvider)
        .serviceConfiguration(configuration.serviceConfiguration)
        .multipartEnabled(true)
        .build()

    override fun createTransferManager(asyncClient: S3AsyncClient): S3TransferManager = S3TransferManager.builder()
        .s3Client(asyncClient)
        .build()

    override fun createStreamReaderExecutor(): ExecutorService = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("artifact-minio-stream-", 0).factory(),
    )
}

class MinioStorageResources(
    val transferManager: S3TransferManager,
    val asyncClient: S3AsyncClient,
    val syncClient: S3Client,
    val streamReaderExecutor: ExecutorService,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val failures = closeMinioResources(
            transferManager,
            asyncClient,
            syncClient,
            streamReaderExecutor,
        )
        val primary = failures.firstOrNull()
        if (primary != null) {
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }
}

private fun closeMinioResources(
    transferManager: S3TransferManager?,
    asyncClient: S3AsyncClient?,
    syncClient: S3Client?,
    streamReaderExecutor: ExecutorService?,
): List<Throwable> = listOfNotNull(
    transferManager?.let { runCatching { it.close() }.exceptionOrNull() },
    asyncClient?.let { runCatching { it.close() }.exceptionOrNull() },
    syncClient?.let { runCatching { it.close() }.exceptionOrNull() },
    streamReaderExecutor?.let { runCatching { it.close() }.exceptionOrNull() },
)
