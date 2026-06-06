package maple.calculator.parser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * Result of parsing a single snapshot JSONL line. `null` payload signals
 * the line is intentionally skipped (e.g. non-SUCCESS status) — distinct
 * from a parse error, which the parser surfaces as a [RuntimeException].
 */
data class SnapshotRecord(
    val ocid: String,
    val body: JsonNode,
)

@Component
class SnapshotLineParser(
    private val objectMapper: ObjectMapper,
) {
    /**
     * Parse one JSONL line. Returns `null` if the line's `status` field is
     * not "SUCCESS" or the body is missing — these are valid skips, not errors.
     */
    fun parse(line: String): SnapshotRecord? {
        val node = objectMapper.readTree(line)
        if (node.path("status").asText() != "SUCCESS") return null
        val body = node.path("body").takeIf { !it.isMissingNode && !it.isNull } ?: return null
        return SnapshotRecord(ocid = node.path("key").asText(""), body = body)
    }
}
