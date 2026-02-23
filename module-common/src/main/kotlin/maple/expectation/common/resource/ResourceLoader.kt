@file:JvmName("ResourceLoader")

package maple.expectation.common.resource

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Utility class for loading resources from classpath.
 *
 * <p><strong>SRP:</strong> Single responsibility - resource loading only.
 *
 * <p><strong>Exception Handling:</strong> Converts IOException to RuntimeException
 *
 * <h3>Usage:</h3>
 *
 * ```kotlin
 * val luaScript = ResourceLoader().loadResourceAsString("lua/script.lua")
 * ```
 */
class ResourceLoader {

    /**
     * Load resource from classpath as String.
     *
     * <p>P2 Fix: Catches IOException and re-wraps as IllegalStateException.
     * Previous implementation let IOException propagate (not caught by Kotlin).
     *
     * @param path Resource path (e.g., "lua/script.lua")
     * @return Resource content as UTF-8 string
     * @throws IllegalStateException if resource not found or read error occurs
     */
    fun loadResourceAsString(path: String): String {
        return try {
            getResourceAsStream(path).use { inputStream ->
                String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
            }
        } catch (e: IOException) {
            throw IllegalStateException("Failed to read resource: $path", e)
        }
    }

    /**
     * Load resource from classpath as InputStream.
     *
     * <p>Caller is responsible for closing the stream.
     *
     * @param path Resource path
     * @return InputStream (caller must close)
     * @throws IllegalStateException if resource not found
     */
    fun loadResourceAsStream(path: String): InputStream {
        return getResourceAsStream(path)
    }

    /**
     * Get resource stream from classpath.
     *
     * @param path Resource path
     * @return InputStream
     * @throws IllegalStateException if resource not found
     */
    private fun getResourceAsStream(path: String): InputStream {
        val inputStream = javaClass.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("Required resource not found: $path")
        return inputStream
    }
}
