package maple.externalapi.infra.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiPayloadRef
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LocalExternalApiArtifactStoreAdapter(
    @Value("\${external-api.store.base-path:/data/external-api}")
    private val basePath: String,
) : ExternalApiArtifactStorePort {

    private val log = LoggerFactory.getLogger(LocalExternalApiArtifactStoreAdapter::class.java)

    override fun store(
        endpoint: ExternalApiEndpoint,
        key: String,
        data: ByteArray,
    ): ExternalApiPayloadRef {
        val compressed = gzipCompress(data)
        val hash = sha256(compressed)
        val filePath = resolvePath(endpoint, key)

        filePath.parent.toFile().mkdirs()
        val tempFile = filePath.resolveSibling(filePath.fileName.toString() + ".tmp")
        Files.write(tempFile, compressed)
        Files.move(tempFile, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

        log.info("[ArtifactStore] stored: endpoint={}, key={}, size={}bytes, hash={}", endpoint, key, compressed.size, hash.take(16))

        return ExternalApiPayloadRef(
            artifactUri = filePath.toString(),
            sha256 = hash,
            sizeBytes = compressed.size.toLong(),
        )
    }

    override fun read(
        endpoint: ExternalApiEndpoint,
        key: String,
    ): ByteArray? {
        val filePath = resolvePath(endpoint, key)
        if (!Files.exists(filePath)) return null
        return GZIPInputStream(Files.readAllBytes(filePath).inputStream()).use { it.readAllBytes() }
    }

    override fun listStoredKeys(endpoint: ExternalApiEndpoint): List<String> {
        val dir = Paths.get(basePath, endpoint.storageSubDir())
        if (!Files.exists(dir)) return emptyList()
        return Files.walk(dir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".json.gz") }
                .map { it.fileName.toString().removeSuffix(".json.gz") }
                .toList()
        }
    }

    private fun resolvePath(endpoint: ExternalApiEndpoint, key: String): Path {
        val sanitized = key.replace("/", "_").replace("\\", "_")
        val shard = sanitized.take(2)
        return Paths.get(basePath, endpoint.storageSubDir(), shard, "$sanitized.json.gz")
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
