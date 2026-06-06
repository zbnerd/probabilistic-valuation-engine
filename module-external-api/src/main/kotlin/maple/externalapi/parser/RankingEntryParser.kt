package maple.externalapi.parser

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * One parsed ranking entry from a Nexon ranking API response, suitable for
 * direct submission to a `ChunkedSnapshotSink`.
 *
 * - [characterName] is the `character_name` field, used as the chunk record key
 * - [bodyBytes] is the re-serialized JSON of the original entry node, used as
 *   the chunk record body
 */
data class RankingEntry(
    val characterName: String,
    val bodyBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Parses Nexon ranking API responses into a list of `RankingEntry`. The owning
 * phase (`RankingFetchPhase`) does not import `ObjectMapper`; all JSON access
 * lives here.
 */
@Component
class RankingEntryParser(
    private val objectMapper: ObjectMapper,
) {
    /**
     * Parse the `ranking` array of a Nexon ranking response. Entries without a
     * `character_name` field are skipped. Returns an empty list when the
     * response has no `ranking` array.
     */
    fun parseEntries(responseBody: ByteArray): List<RankingEntry> {
        val root = objectMapper.readTree(responseBody)
        val rankingArray = root.path("ranking")
        if (!rankingArray.isArray) return emptyList()

        val entries = mutableListOf<RankingEntry>()
        for (node in rankingArray) {
            val name = node.path("character_name").asText()
            if (name.isBlank()) continue
            entries.add(
                RankingEntry(
                    characterName = name,
                    bodyBytes = objectMapper.writeValueAsBytes(node),
                ),
            )
        }
        return entries
    }
}
