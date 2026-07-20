package maple.pipeline.artifact.write

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.storage.ConditionalObjectStorage

interface GzipArtifactSession : AutoCloseable {
    val output: OutputStream

    fun complete(uncompressedBytes: Long): CompletableFuture<ArtifactReceipt>

    fun abort(cause: Throwable): CompletableFuture<ArtifactReceipt>
}

internal class DefaultGzipArtifactSession(
    private val key: ArtifactKey,
    private val tempFile: Path,
    private val objectStorage: ConditionalObjectStorage,
    private val uploadExecutor: Executor,
    compressionLevel: Int,
) : GzipArtifactSession {
    private val state = AtomicReference(SessionState.OPEN)
    private val digest = MessageDigest.getInstance("SHA-256")
    private val digestOutput = DigestOutputStream(Files.newOutputStream(tempFile), digest)
    private val gzipOutput = runCatching { LevelGzipOutputStream(digestOutput, compressionLevel) }
        .getOrElse { failure -> closeDigestAfterConstructionFailure(failure) }

    override val output: OutputStream
        get() = gzipOutput

    override fun complete(uncompressedBytes: Long): CompletableFuture<ArtifactReceipt> {
        if (!state.compareAndSet(SessionState.OPEN, SessionState.COMPLETING)) {
            return terminalFailure()
        }
        val finalized = runCatching {
            gzipOutput.close()
            FinalizedArtifact(
                compressedBytes = Files.size(tempFile),
                contentSha256 = HexFormat.of().formatHex(digest.digest()),
            )
        }
        val receiptFuture = finalized.fold(
            onSuccess = { artifact -> upload(artifact, uncompressedBytes) },
            onFailure = { failure -> CompletableFuture.failedFuture(failure) },
        )
        val lifetimeFuture = receiptFuture.whenComplete { _, failure -> deleteTempFile(failure) }
        return lifetimeFuture.thenApply { receipt -> receipt }
    }

    override fun abort(cause: Throwable): CompletableFuture<ArtifactReceipt> {
        if (!state.compareAndSet(SessionState.OPEN, SessionState.ABORTED)) {
            return terminalFailure()
        }
        cleanupResources()?.let { cleanupFailure ->
            if (cleanupFailure !== cause) cause.addSuppressed(cleanupFailure)
        }
        return CompletableFuture.failedFuture(cause)
    }

    override fun close() {
        if (state.compareAndSet(SessionState.OPEN, SessionState.ABORTED)) {
            cleanupResources()?.let { cleanupFailure -> throw cleanupFailure }
        }
    }

    private fun upload(
        artifact: FinalizedArtifact,
        uncompressedBytes: Long,
    ): CompletableFuture<ArtifactReceipt> {
        val putFuture = runCatching {
            CompletableFuture.completedFuture(tempFile)
                .thenComposeAsync(
                    { path -> objectStorage.putFileAsync(key.value, path) },
                    uploadExecutor,
                )
        }.getOrElse { failure -> CompletableFuture.failedFuture(failure) }
        return putFuture.thenApply { putResult -> artifact.toReceipt(putResult, uncompressedBytes) }
    }

    private fun FinalizedArtifact.toReceipt(
        putResult: PutResult,
        uncompressedBytes: Long,
    ): ArtifactReceipt = ArtifactReceipt(
        key = key,
        compressedBytes = compressedBytes,
        uncompressedBytes = uncompressedBytes,
        contentSha256 = contentSha256,
        backendTag = putResult.checksum,
    )

    private fun cleanupResources(): Throwable? {
        val failures = sequenceOf(
            runCatching { gzipOutput.close() }.exceptionOrNull(),
            runCatching { Files.deleteIfExists(tempFile) }.exceptionOrNull(),
        ).filterNotNull()
            .toList()
        val primary = failures.firstOrNull() ?: return null
        failures.drop(1)
            .filter { failure -> failure !== primary }
            .forEach(primary::addSuppressed)
        return primary
    }

    private fun deleteTempFile(lifetimeFailure: Throwable?) {
        val cleanupFailure = runCatching { Files.deleteIfExists(tempFile) }.exceptionOrNull() ?: return
        if (lifetimeFailure == null) throw cleanupFailure
        if (cleanupFailure !== lifetimeFailure) lifetimeFailure.addSuppressed(cleanupFailure)
    }

    private fun closeDigestAfterConstructionFailure(failure: Throwable): Nothing {
        runCatching { digestOutput.close() }
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
        throw failure
    }

    private fun terminalFailure(): CompletableFuture<ArtifactReceipt> = CompletableFuture.failedFuture(
        IllegalStateException("artifact gzip session already completed or aborted"),
    )

    private data class FinalizedArtifact(
        val compressedBytes: Long,
        val contentSha256: String,
    )

    private enum class SessionState {
        OPEN,
        COMPLETING,
        ABORTED,
    }
}

private class LevelGzipOutputStream(
    output: OutputStream,
    compressionLevel: Int,
) : GZIPOutputStream(output) {
    init {
        require(compressionLevel in Deflater.NO_COMPRESSION..Deflater.BEST_COMPRESSION) {
            "gzip compression level must be between 0 and 9"
        }
        def.setLevel(compressionLevel)
    }
}
