package maple.calculator.writer

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.ArtifactPrefix
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import maple.pipeline.artifact.storage.PutIfAbsentResult
import maple.pipeline.artifact.storage.StorageObjectPage

/**
 * Stub [ObjectStorage] for unit tests. All methods throw
 * [NotImplementedError] by default. Tests override only the methods they
 * exercise. Avoids a mocking-framework dependency.
 */
open class StubObjectStorage : ConditionalObjectStorage {

    /** If set, [putFileAsync] copies the uploaded file's bytes into this buffer. */
    var capturedStream: ByteArray? = null

    /** Override to inject behavior into [putFileAsync]. */
    open fun handlePutFileAsync(key: String, path: Path): PutResult {
        val bytes = Files.readAllBytes(path)
        capturedStream = bytes
        return PutResult(key, bytes.size.toLong(), "stub-etag-${UUID.randomUUID()}")
    }

    final override fun putFileAsync(
        key: String,
        path: Path,
    ): CompletableFuture<PutResult> = try {
        CompletableFuture.completedFuture(handlePutFileAsync(key, path))
    } catch (e: Exception) {
        CompletableFuture.failedFuture(e)
    }

    // --- Unused methods throw to surface accidental test dependencies ---

    override fun put(key: String, data: ByteArray): PutResult = throw NotImplementedError()

    @Deprecated("Stub default; not exercised by tests.")
    override fun putStream(key: String, input: InputStream): PutResult = throw NotImplementedError()
    override fun putFile(key: String, path: Path): PutResult = throw NotImplementedError()
    override fun putStreamMultipart(
        key: String,
        input: InputStream,
    ): CompletableFuture<PutResult> = throw NotImplementedError()
    override fun get(key: String): ByteArray = throw NotImplementedError()
    override fun getStream(key: String): InputStream = throw NotImplementedError()
    override fun delete(key: String) = throw NotImplementedError()
    override fun exists(key: String): Boolean = throw NotImplementedError()
    override fun listByPrefix(prefix: String): List<ObjectInfo> = throw NotImplementedError()
    override fun deleteByPrefix(prefix: String): Long = throw NotImplementedError()
    override fun calculatePrefixSize(prefix: String): Long = throw NotImplementedError()
    override fun getLastModified(key: String): Instant? = throw NotImplementedError()
    override fun putIfAbsent(key: String, data: ByteArray): CompletionStage<PutIfAbsentResult> = throw NotImplementedError()
    override fun listPage(prefix: ArtifactPrefix, afterKey: ArtifactKey?, limit: Int): StorageObjectPage = throw NotImplementedError()
}
