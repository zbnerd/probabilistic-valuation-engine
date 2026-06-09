package maple.externalapi.infra.storage

import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.stream.Collectors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiPayloadRef
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LocalExternalApiArtifactStoreAdapter(
    @Value("\${external-api.store.base-path:../data}")
    private val basePath: String,
) : ExternalApiArtifactStorePort {

    private val log = LoggerFactory.getLogger(LocalExternalApiArtifactStoreAdapter::class.java)

    override fun store(
        endpoint: ExternalApiEndpoint,
        key: String,
        data: ByteArray,
    ): ExternalApiPayloadRef {
        // Issue #1128: CPU offload — gzipCompress + sha256 on Dispatchers.Default.
        // Sync port method preserved. runBlocking bridges to Default dispatcher.
        // Caller (multi-threaded VT submit) self-blocks for ~ms; other submit unaffected.
        val (compressed, hash) = runBlocking(Dispatchers.Default) {
            val c = gzipCompress(data)
            val h = sha256(c)
            c to h
        }
        val filePath = resolvePath(endpoint, key)

        filePath.parent.toFile().mkdirs()
        val tempFile = filePath.resolveSibling("${filePath.fileName}.${UUID.randomUUID()}.tmp")
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
    ): ByteArray {
        val filePath = resolvePath(endpoint, key)
        if (!Files.exists(filePath)) return ByteArray(0)
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

    override fun listRuns(): List<String> {
        val runsDir = Paths.get(basePath, "runs")
        if (!Files.exists(runsDir)) return emptyList()
        return Files.list(runsDir).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .collect(Collectors.toList())
        }
    }

    override fun deleteRun(runId: String): Long {
        val runDir = Paths.get(basePath, "runs", runId)
        if (!Files.exists(runDir)) return 0L
        var deletedBytes = 0L
        Files.walkFileTree(runDir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                deletedBytes += attrs.size()
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        return deletedBytes
    }

    override fun deleteAll(endpoint: ExternalApiEndpoint): Int {
        val dir = Paths.get(basePath, endpoint.storageSubDir())
        if (!Files.exists(dir)) return 0
        var count = 0
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                count++
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(d: Path, exc: java.io.IOException?): FileVisitResult {
                Files.delete(d)
                return FileVisitResult.CONTINUE
            }
        })
        return count
    }

    override fun fileExists(relativePath: String): Boolean =
        Files.exists(Paths.get(basePath, relativePath))

    override fun calculateDirectorySize(relativePath: String): Long {
        val dir = Paths.get(basePath, relativePath)
        if (!Files.exists(dir)) return 0L
        return Files.walk(dir, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
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
