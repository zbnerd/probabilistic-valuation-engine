package maple.calculator.storage

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Collectors
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LocalObjectStorageAdapter(
    @Value("\${calculator.store.input-base-path:../data}")
    private val basePath: String,
) : ObjectStorage {

    override fun openInputStream(objectKey: String): InputStream {
        val path = Paths.get(basePath, objectKey)
        return Files.newInputStream(path)
    }

    override fun openOutputStream(objectKey: String): OutputStream {
        val path = Paths.get(basePath, objectKey)
        path.parent?.let { Files.createDirectories(it) }
        return Files.newOutputStream(path)
    }

    override fun exists(objectKey: String): Boolean = Files.exists(Paths.get(basePath, objectKey))

    override fun listDirectories(prefix: String): List<String> {
        val dir = Paths.get(basePath, prefix)
        if (!Files.exists(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .collect(Collectors.toList())
        }
    }

    override fun deleteDirectory(prefix: String): Long {
        val dir = Paths.get(basePath, prefix)
        if (!Files.exists(dir)) return 0L
        var deletedBytes = 0L
        Files.walkFileTree(
            dir,
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

    override fun calculateDirectorySize(prefix: String): Long {
        val dir = Paths.get(basePath, prefix)
        if (!Files.exists(dir)) return 0L
        return Files.walk(dir, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
        }
    }
}
