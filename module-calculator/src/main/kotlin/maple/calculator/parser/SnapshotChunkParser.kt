package maple.calculator.parser

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class SnapshotChunkParser(
    private val objectMapper: ObjectMapper,
    private val equipmentParser: SnapshotEquipmentParser,
) {

    sealed class Outcome {
        data object Skipped : Outcome()
        data class Parsed(val items: List<FlatItem>) : Outcome()
    }

    fun parse(line: String): Outcome {
        val node = objectMapper.readTree(line)
        if (node.path("status").asText() != "SUCCESS") return Outcome.Skipped
        val body = node.path("body").takeIf { !it.isMissingNode && !it.isNull } ?: return Outcome.Skipped
        val ocid = node.path("key").asText("")

        val items = equipmentParser.parseAllPresets(body).flatMap { (presetNo, equipmentItems) ->
            equipmentItems.map { FlatItem(ocid, presetNo, it) }
        }
        return Outcome.Parsed(items)
    }
}
