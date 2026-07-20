package maple.pipeline.artifact.storage

import io.micrometer.core.instrument.MeterRegistry
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.stream.Collectors
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.ArtifactPrefix

/** Local filesystem storage with atomic publication and durable directory entries. */
class LocalFsObjectStorage internal constructor(
    private val basePath: Path,
    private val uploadExecutor: Executor,
    @Suppress("unused") private val meterRegistry: MeterRegistry?,
    private val directoryForce: (Path) -> Unit,
) : ConditionalObjectStorage {
    constructor(
        basePath: String,
        uploadExecutor: Executor,
        meterRegistry: MeterRegistry?,
    ) : this(Paths.get(basePath), uploadExecutor, meterRegistry, LocalFsDurability::forceDirectory)

    override fun put(key: String, data: ByteArray): PutResult {
        val destination = prepareDestination(key)
        return withSiblingTemp(destination) { temp ->
            LocalFsDurability.writeAndForce(temp, data)
            publishReplacement(temp, destination)
            PutResult(key, data.size.toLong(), sha256Hex(data))
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun putStream(key: String, input: InputStream): PutResult {
        val destination = prepareDestination(key)
        return withSiblingTemp(destination) { temp ->
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING)
            LocalFsDurability.forceFile(temp)
            val size = Files.size(temp)
            val hash = sha256Hex(temp)
            publishReplacement(temp, destination)
            PutResult(key, size, hash)
        }
    }

    override fun putFile(key: String, path: Path): PutResult {
        require(Files.exists(path)) { "putFile source does not exist: $path" }
        val destination = prepareDestination(key)
        return withSiblingTemp(destination) { temp ->
            Files.copy(path, temp, StandardCopyOption.REPLACE_EXISTING)
            LocalFsDurability.forceFile(temp)
            val size = Files.size(temp)
            val hash = sha256Hex(temp)
            publishReplacement(temp, destination)
            PutResult(key, size, hash)
        }
    }

    override fun putFileAsync(key: String, path: Path): CompletableFuture<PutResult> = CompletableFuture.supplyAsync({ putFile(key, path) }, uploadExecutor)

    override fun putStreamMultipart(key: String, input: InputStream): CompletableFuture<PutResult> = CompletableFuture.supplyAsync({ putStreamWithoutClosing(key, input) }, uploadExecutor)

    override fun putIfAbsent(key: String, data: ByteArray): CompletionStage<PutIfAbsentResult> {
        val snapshot = data.copyOf()
        return CompletableFuture.supplyAsync({ putIfAbsentOnExecutor(key, snapshot) }, uploadExecutor)
    }

    override fun listPage(prefix: ArtifactPrefix, afterKey: ArtifactKey?, limit: Int): StorageObjectPage {
        validatePageRequest(prefix, afterKey, limit)
        val directory = resolve(prefix.value)
        if (!Files.exists(directory)) return StorageObjectPage(emptyList(), null)
        val candidates = Files.walk(directory, FileVisitOption.FOLLOW_LINKS).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .map(::toObjectInfo)
                .sorted(compareBy(ObjectInfo::key))
                .filter { afterKey == null || it.key > afterKey.value }
                .limit(limit.toLong() + 1L)
                .collect(Collectors.toList())
        }
        val hasNext = candidates.size > limit
        val objects = if (hasNext) candidates.subList(0, limit).toList() else candidates.toList()
        val next = if (hasNext) objects.lastOrNull()?.let { ArtifactKey.require(it.key) } else null
        return StorageObjectPage(objects, next)
    }

    override fun get(key: String): ByteArray = Files.readAllBytes(resolve(key))

    override fun getStream(key: String): InputStream = Files.newInputStream(resolve(key))

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    override fun exists(key: String): Boolean = Files.exists(resolve(key))

    override fun listByPrefix(prefix: String): List<ObjectInfo> {
        val directory = resolve(prefix)
        if (!Files.exists(directory)) return emptyList()
        return Files.walk(directory, FileVisitOption.FOLLOW_LINKS).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .map(::toObjectInfo)
                .collect(Collectors.toList())
        }
    }

    override fun deleteByPrefix(prefix: String): Long {
        val directory = resolve(prefix)
        if (!Files.exists(directory)) return 0L
        var deletedBytes = 0L
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    deletedBytes += attrs.size()
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return deletedBytes
    }

    override fun calculatePrefixSize(prefix: String): Long {
        val directory = resolve(prefix)
        if (!Files.exists(directory)) return 0L
        return Files.walk(directory, FileVisitOption.FOLLOW_LINKS).use { paths ->
            paths.filter(Files::isRegularFile).mapToLong(Files::size).sum()
        }
    }

    override fun getLastModified(key: String): Instant? {
        val path = resolve(key)
        if (!Files.exists(path)) return null
        return Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis())
    }

    private fun putStreamWithoutClosing(key: String, input: InputStream): PutResult {
        val destination = prepareDestination(key)
        return withSiblingTemp(destination) { temp ->
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING)
            LocalFsDurability.forceFile(temp)
            val size = Files.size(temp)
            val hash = sha256Hex(temp)
            publishReplacement(temp, destination)
            PutResult(key, size, hash)
        }
    }

    private fun putIfAbsentOnExecutor(key: String, data: ByteArray): PutIfAbsentResult {
        val destination = prepareDestination(key)
        return withSiblingTemp(destination) { temp ->
            LocalFsDurability.writeAndForce(temp, data)
            runCatching { Files.createLink(destination, temp) }
                .fold(
                    onSuccess = {
                        Files.delete(temp)
                        directoryForce(requireNotNull(destination.parent))
                        PutIfAbsentResult.Created(sha256Hex(data))
                    },
                    onFailure = { failure ->
                        if (failure is FileAlreadyExistsException) {
                            val existing = Files.readAllBytes(destination)
                            PutIfAbsentResult.Existing(existing, sha256Hex(existing))
                        } else {
                            throw failure
                        }
                    },
                )
        }
    }

    private fun prepareDestination(key: String): Path {
        val destination = resolve(key)
        Files.createDirectories(requireNotNull(destination.parent))
        return destination
    }

    private fun publishReplacement(temp: Path, destination: Path) {
        Files.move(
            temp,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        directoryForce(requireNotNull(destination.parent))
    }

    private fun <T> withSiblingTemp(destination: Path, operation: (Path) -> T): T {
        val parent = requireNotNull(destination.parent)
        val temp = Files.createTempFile(parent, ".${destination.fileName}-", ".tmp")
        val result = runCatching { operation(temp) }
        val cleanup = runCatching { Files.deleteIfExists(temp) }
        return finishWithCleanup(result, cleanup)
    }

    private fun <T> finishWithCleanup(result: Result<T>, cleanup: Result<Boolean>): T {
        val failure = result.exceptionOrNull()
        val cleanupFailure = cleanup.exceptionOrNull()
        if (failure != null) {
            if (cleanupFailure != null) failure.addSuppressed(cleanupFailure)
            throw failure
        }
        if (cleanupFailure != null) throw cleanupFailure
        return result.getOrThrow()
    }

    private fun toObjectInfo(path: Path): ObjectInfo = ObjectInfo(
        key = basePath.relativize(path).toString().replace('\\', '/'),
        size = Files.size(path),
        lastModified = Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()),
    )

    private fun resolve(key: String): Path {
        require(!key.startsWith('/')) { "key must be relative (no leading slash): $key" }
        require(!key.contains("..")) { "key must not contain '..': $key" }
        return basePath.resolve(key)
    }

    private fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            generateSequence { input.read(buffer).takeIf { it >= 0 } }
                .takeWhile { it > 0 }
                .forEach { count -> digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal object LocalFsDurability {
    fun writeAndForce(path: Path, data: ByteArray) {
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(data)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    fun forceFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
    }

    fun forceDirectory(path: Path) {
        FileChannel.open(path, StandardOpenOption.READ).use { channel -> channel.force(true) }
    }
}
