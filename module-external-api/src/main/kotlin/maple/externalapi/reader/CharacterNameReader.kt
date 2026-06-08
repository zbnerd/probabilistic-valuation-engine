package maple.externalapi.reader

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import org.springframework.stereotype.Component

/**
 * Reads GZIP-compressed JSONL ranking-chunk files and returns the distinct set
 * of `key` fields (character names) found across all chunks in a directory.
 * Encapsulates GZIP + JSON parsing so the calling phase does not import
 * `GZIPInputStream` or `ObjectMapper`.
 *
 * Ordering is deterministic: chunks are processed in sorted path order, and
 * the returned list preserves first-seen insertion order of names.
 */
@Component
class CharacterNameReader(
    private val objectMapper: ObjectMapper,
) {
    /**
     * Read all `*.jsonl.gz` files in [chunksDir] and return the distinct `key`
     * field values from each non-blank line. Returns an empty list when the
     * directory does not exist.
     */
    fun readDistinctKeys(chunksDir: Path): List<String> {
        if (!Files.exists(chunksDir)) return emptyList()

        val names = linkedSetOf<String>()
        Files.list(chunksDir).use { stream ->
            stream.filter { it.toString().endsWith(".jsonl.gz") }
                .sorted()
                .forEach { chunkFile ->
                    GZIPInputStream(BufferedInputStream(Files.newInputStream(chunkFile))).bufferedReader().use { reader ->
                        reader.lineSequence().forEach { line ->
                            if (line.isNotBlank()) {
                                val key = objectMapper.readTree(line).path("key").asText()
                                if (key.isNotBlank()) names.add(key)
                            }
                        }
                    }
                }
        }
        return names.toList()
    }
}
