package maple.calculator.writer

import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import java.io.InputStream
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Stub [ObjectStorage] for unit tests. All methods throw
 * [NotImplementedError] by default. Tests override only the methods they
 * exercise. Avoids a mocking-framework dependency.
 */
open class StubObjectStorage : ObjectStorage {

    /** If set, [putStreamMultipart] copies the input into this buffer. */
    var capturedStream: ByteArray? = null

    /** Override to inject behavior into [putStreamMultipart]. */
    open fun handlePutStreamMultipart(key: String, input: InputStream): PutResult {
        val bytes = input.readBytes()
        capturedStream = bytes
        return PutResult(key, bytes.size.toLong(), "stub-etag-${UUID.randomUUID()}")
    }

    final override fun putStreamMultipart(
        key: String,
        input: InputStream,
    ): CompletableFuture<PutResult> = try {
        CompletableFuture.completedFuture(handlePutStreamMultipart(key, input))
    } catch (e: Exception) {
        CompletableFuture.failedFuture(e)
    }

    // --- Unused methods throw to surface accidental test dependencies ---

    override fun put(key: String, data: ByteArray): PutResult = throw NotImplementedError()
    @Deprecated("Stub default; not exercised by tests.")
    override fun putStream(key: String, input: InputStream): PutResult = throw NotImplementedError()
    override fun putFile(key: String, path: Path): PutResult = throw NotImplementedError()
    override fun putFileAsync(key: String, path: Path): CompletableFuture<PutResult> = throw NotImplementedError()
    override fun get(key: String): ByteArray = throw NotImplementedError()
    override fun getStream(key: String): InputStream = throw NotImplementedError()
    override fun delete(key: String) = throw NotImplementedError()
    override fun exists(key: String): Boolean = throw NotImplementedError()
    override fun listByPrefix(prefix: String): List<ObjectInfo> = throw NotImplementedError()
    override fun deleteByPrefix(prefix: String): Long = throw NotImplementedError()
    override fun calculatePrefixSize(prefix: String): Long = throw NotImplementedError()
    override fun getLastModified(key: String): Instant? = throw NotImplementedError()
}