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
    streamFactory: GzipArtifactStreamFactory = DefaultGzipArtifactStreamFactory,
) : GzipArtifactSession {
    private val state = AtomicReference(SessionState.OPEN)
    private val streams = streamFactory.open(tempFile, compressionLevel)
    private val digest = streams.digest
    private val gzipOutput = streams.gzipOutput

    override val output: OutputStream
        get() = gzipOutput

    override fun complete(uncompressedBytes: Long): CompletableFuture<ArtifactReceipt> {
        if (!state.compareAndSet(SessionState.OPEN, SessionState.COMPLETING)) {
            return terminalFailure()
        }
        val finalized = closeStreams()?.let { closeFailure ->
            Result.failure(closeFailure)
        } ?: runCatching {
            FinalizedArtifact(Files.size(tempFile), HexFormat.of().formatHex(digest.digest()))
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
            closeStreams(),
            runCatching { Files.deleteIfExists(tempFile) }.exceptionOrNull(),
        ).filterNotNull()
            .toList()
        val primary = failures.firstOrNull() ?: return null
        failures.drop(1)
            .filter { failure -> failure !== primary }
            .forEach(primary::addSuppressed)
        return primary
    }

    private fun closeStreams(): Throwable? {
        val failures = sequenceOf(
            runCatching { gzipOutput.close() }.exceptionOrNull(),
            runCatching { streams.digestOutput.close() }.exceptionOrNull(),
            runCatching { streams.fileOutput.close() }.exceptionOrNull(),
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

internal fun interface GzipArtifactStreamFactory {
    fun open(tempFile: Path, compressionLevel: Int): GzipArtifactStreams
}

internal data class GzipArtifactStreams(
    val digest: MessageDigest,
    val fileOutput: OutputStream,
    val digestOutput: OutputStream,
    val gzipOutput: OutputStream,
)

private object DefaultGzipArtifactStreamFactory : GzipArtifactStreamFactory {
    override fun open(tempFile: Path, compressionLevel: Int): GzipArtifactStreams {
        val digest = MessageDigest.getInstance("SHA-256")
        val fileOutput = Files.newOutputStream(tempFile)
        val digestOutput = DigestOutputStream(fileOutput, digest)
        val gzipOutput = runCatching { LevelGzipOutputStream(digestOutput, compressionLevel) }
            .getOrElse { failure -> closeAfterConstructionFailure(failure, digestOutput, fileOutput) }
        return GzipArtifactStreams(digest, fileOutput, digestOutput, gzipOutput)
    }

    private fun closeAfterConstructionFailure(
        failure: Throwable,
        vararg outputs: OutputStream,
    ): Nothing {
        outputs.forEach { output ->
            runCatching { output.close() }
                .exceptionOrNull()
                ?.takeIf { closeFailure -> closeFailure !== failure }
                ?.let(failure::addSuppressed)
        }
        throw failure
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
