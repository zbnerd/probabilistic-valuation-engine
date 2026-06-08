package maple.restcontroller.read

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import maple.expectation.util.GzipUtils
import org.springframework.stereotype.Component

@Component
class ReadModelDocumentExtractor(
    private val objectMapper: ObjectMapper,
) {
    fun extract(
        userIgn: String,
        compressed: ByteArray,
        row: Map<String, Any?>,
    ): V6ExpectationResponse {
        val json = GzipUtils.decompress(compressed)
        val tree = objectMapper.readTree(json)

        val equipmentNode = tree["equipment"]

        @Suppress("UNCHECKED_CAST")
        val equipment: List<Map<String, Any?>> = if (equipmentNode != null && !equipmentNode.isNull) {
            objectMapper.readValue(
                equipmentNode.toString(),
                objectMapper.typeFactory.constructCollectionType(List::class.java, Map::class.java),
            ) as List<Map<String, Any?>>
        } else {
            emptyList()
        }

        return V6ExpectationResponse(
            userIgn = userIgn,
            presetNo = tree["presetNo"]?.asInt() ?: (row["preset_no"] as Number).toInt(),
            totalCost = tree["summary"]?.get("totalCost")?.decimalValue()
                ?: row["total_cost"] as? BigDecimal
                ?: BigDecimal.ZERO,
            equipmentCount = tree["summary"]?.get("equipmentCount")?.asInt()
                ?: (row["equipment_count"] as? Number)?.toInt()
                ?: 0,
            equipment = equipment,
            calculatedAt = tree["metadata"]?.get("calculatedAt")?.asText()?.let(Instant::parse)
                ?: (row["calculated_at"] as? Timestamp)?.toInstant()
                ?: Instant.now(),
        )
    }
}
