package maple.calculator.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

@Component
class LocalObjectStorageAdapter(
    @Value("\${calculator.store.input-base-path:./external-api-data}")
    private val basePath: String,
) : ObjectStorage {

    override fun openInputStream(objectKey: String): InputStream {
        val path = Paths.get(basePath, objectKey)
        return Files.newInputStream(path)
    }

    override fun exists(objectKey: String): Boolean {
        return Files.exists(Paths.get(basePath, objectKey))
    }
}
