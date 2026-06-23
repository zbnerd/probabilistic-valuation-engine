package maple.common.parser

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Token-stream parser for gz-compressed JSONL input. Emits one
 * [Map] per top-level JSON object, without materializing a full
 * List or intermediate `byte[]` per record.
 *
 * Stateless and thread-safe; safe to inject as a Spring singleton.
 *
 * Implementation: reads the gz stream as lines (BufferedReader over
 * UTF-8 decoded GZIPInputStream) and parses each non-empty line as a
 * standalone JSON object. This avoids the token-stream recovery pitfalls
 * of Jackson's JsonParser on malformed input mid-record.
 *
 * Memory: O(1) per record (only the current line + parsed Map are in memory).
 */
class StreamingChunkParser(
    private val objectMapper: ObjectMapper,
    private val skipMalformed: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(StreamingChunkParser::class.java)

    /**
     * Stream-parse a gz-compressed JSONL input into a cold [Flow] of record Maps.
     */
    fun parse(input: InputStream): Flow<Map<String, Any>> = flow {
        GZIPInputStream(input).use { gz ->
            BufferedReader(InputStreamReader(gz, StandardCharsets.UTF_8)).use { reader ->
                var records = 0L
                var skipped = 0L
                var lineNo = 0L

                reader.lineSequence().forEach { line ->
                    lineNo++
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEach

                    try {
                        val node = objectMapper.readValue(trimmed, ObjectNode::class.java)
                        @Suppress("UNCHECKED_CAST")
                        emit(objectMapper.convertValue(node, Map::class.java) as Map<String, Any>)
                        records++
                    } catch (ex: Exception) {
                        if (!skipMalformed) {
                            log.error("[ChunkParser] failing on malformed record line={}", lineNo)
                            throw ex
                        }
                        skipped++
                        log.error("[ChunkParser] skipped malformed record line={}: {}", lineNo, ex.message)
                    }
                }

                log.info("[ChunkParser] done records={} skipped={}", records, skipped)
            }
        }
    }

    /**
     * Convenience helper for callers that materialize the entire stream
     * (e.g. cold paths that need a `List`).
     */
    suspend fun parseToList(input: InputStream): List<Map<String, Any>> =
        parse(input).toList()
}
