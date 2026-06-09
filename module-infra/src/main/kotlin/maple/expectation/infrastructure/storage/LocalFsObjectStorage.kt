package maple.expectation.infrastructure.storage

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.stream.Collectors

/**
 * Local filesystem implementation of [ObjectStorage]. Used when
 * `storage.backend=local`. Acts as a hot-spare rollback target in production.
 */
@Component
class LocalFsObjectStorage(
    @Value("\${storage.local.base-path:../data}") private val basePath: String,
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private val meterRegistry: MeterRegistry?,
) : ObjectStorage {

    override fun put(key: String, data: ByteArray): PutResult {
        val path = resolve(key)
        path.parent.toFile().mkdirs()
        val temp = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        Files.write(temp, data)
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return PutResult(key, data.size.toLong(), sha256Hex(data))
    }

    override fun putStream(key: String, input: java.io.InputStream): PutResult {
        val path = resolve(key)
        path.parent.toFile().mkdirs()
        val temp = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        val bytes = input.use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val hash = sha256Hex(Files.readAllBytes(path))
        return PutResult(key, bytes, hash)
    }

    override fun get(key: String): ByteArray = Files.readAllBytes(resolve(key))

    override fun getStream(key: String): java.io.InputStream = Files.newInputStream(resolve(key))

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    override fun exists(key: String): Boolean = Files.exists(resolve(key))

    override fun listByPrefix(prefix: String): List<ObjectInfo> {
        val dir = resolve(prefix)
        if (!Files.exists(dir)) return emptyList()
        val base = Paths.get(basePath)
        return Files.walk(dir, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { p ->
                    val relKey = base.relativize(p).toString().replace('\\', '/')
                    ObjectInfo(
                        key = relKey,
                        size = Files.size(p),
                        lastModified = Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis()),
                    )
                }
                .collect(Collectors.toList())
        }
    }

    override fun deleteByPrefix(prefix: String): Long {
        val dir = resolve(prefix)
        if (!Files.exists(dir)) return 0L
        var deletedBytes = 0L
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
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

    override fun calculatePrefixSize(prefix: String): Long {
        val dir = resolve(prefix)
        if (!Files.exists(dir)) return 0L
        return Files.walk(dir, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
        }
    }

    override fun getLastModified(key: String): Instant? {
        val p = resolve(key)
        if (!Files.exists(p)) return null
        return Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis())
    }

    private fun resolve(key: String): Path {
        require(!key.startsWith("/")) { "key must be relative (no leading slash): $key" }
        require(!key.contains("..")) { "key must not contain '..': $key" }
        return Paths.get(basePath, key)
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
