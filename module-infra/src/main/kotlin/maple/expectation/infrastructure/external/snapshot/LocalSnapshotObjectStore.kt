package maple.expectation.infrastructure.external.snapshot

import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.core.port.out.SnapshotObjectStoreResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Component
class LocalSnapshotObjectStore(
    @Value("\${snapshot.store.local.base-path:/data/snapshots}")
    private val basePath: String
) : SnapshotObjectStore {

    override fun put(snapshot: CalculationSnapshot, data: ByteArray): SnapshotObjectStoreResult {
        val compressed = gzipCompress(data)
        val hash = sha256(compressed)
        val fullPath = resolveFullPath(snapshot.objectKey)

        fullPath.parent.toFile().mkdirs()

        FileOutputStream(fullPath.toFile()).use { fos ->
            fos.write(compressed)
        }

        return SnapshotObjectStoreResult(
            objectKey = snapshot.objectKey,
            compressedSize = compressed.size.toLong(),
            hash = hash
        )
    }

    override fun get(objectKey: String): ByteArray {
        val fullPath = resolveFullPath(objectKey)
        val compressed = Files.readAllBytes(fullPath)
        return gzipDecompress(compressed)
    }

    override fun delete(objectKey: String) {
        val fullPath = resolveFullPath(objectKey)
        Files.deleteIfExists(fullPath)
    }

    private fun resolveFullPath(objectKey: String): Path {
        val logicalKey = objectKey.removePrefix("/")
        return Paths.get(basePath, logicalKey)
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gzipDecompress(compressed: ByteArray): ByteArray {
        GZIPInputStream(compressed.inputStream()).use { return it.readAllBytes() }
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
