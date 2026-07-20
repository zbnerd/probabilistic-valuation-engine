package maple.pipeline.artifact.storage

import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.transfer.s3.S3TransferManager

class MinioStorageResources(
    val transferManager: S3TransferManager,
    val asyncClient: S3AsyncClient,
    val syncClient: S3Client,
    val streamReaderExecutor: ExecutorService,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val failures = listOf(
            runCatching { transferManager.close() },
            runCatching { asyncClient.close() },
            runCatching { syncClient.close() },
            runCatching { streamReaderExecutor.close() },
        ).mapNotNull(Result<Unit>::exceptionOrNull)
        val primary = failures.firstOrNull()
        if (primary != null) {
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }
}
