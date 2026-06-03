@file:JvmName("HashUtils")

package maple.expectation.util

object HashUtils {
    @JvmStatic
    fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
