package maple.calculator.storage

import java.io.InputStream
import java.io.OutputStream

interface ObjectStorage {
    fun openInputStream(objectKey: String): InputStream
    fun openOutputStream(objectKey: String): OutputStream
    fun exists(objectKey: String): Boolean

    fun listDirectories(prefix: String): List<String>

    fun deleteDirectory(prefix: String): Long

    fun calculateDirectorySize(prefix: String): Long
}
