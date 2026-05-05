package maple.calculator.storage

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LocalObjectStorageAdapter(
    @Value("\${calculator.store.input-base-path:./external-api-data}")
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
}
