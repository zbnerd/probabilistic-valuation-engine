package maple.calculator.storage

import java.io.InputStream

interface ObjectStorage {
    fun openInputStream(objectKey: String): InputStream
    fun exists(objectKey: String): Boolean
}
