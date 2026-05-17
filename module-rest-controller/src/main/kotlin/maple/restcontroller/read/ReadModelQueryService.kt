package maple.restcontroller.read

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.math.BigDecimal
import java.time.Instant

class ReadModelQueryService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param requests userIgn -> presetNo mapping
     * @return userIgn -> V6ExpectationResponse for hits only
     */
    fun batchQuery(requests: Map<String, Int>): Map<String, V6ExpectationResponse> {
        if (requests.isEmpty()) return emptyMap()

        val userIgns = requests.keys.toList()
        val presetNos = requests.values.distinct()

        val sql = """
            SELECT user_ign, preset_no, document, total_cost, equipment_count, calculated_at
            FROM character_equipment_read_model
            WHERE user_ign IN (:userIgns)
              AND preset_no IN (:presetNos)
              AND user_ign IS NOT NULL
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("userIgns", userIgns)
            .addValue("presetNos", presetNos)

        @Suppress("UNCHECKED_CAST")
        val rows = jdbc.queryForList(sql, params, Map::class.java) as List<Map<String, Any>>

        return rows.associate { row ->
            val userIgn = row["user_ign"].toString()
            val compressed = row["document"] as ByteArray
            val json = GzipUtils.decompress(compressed)
            val tree = objectMapper.readTree(json)

            userIgn to V6ExpectationResponse(
                userIgn = userIgn,
                presetNo = tree.get("presetNo")?.asInt() ?: 1,
                totalCost = tree.get("summary")?.get("totalCost")?.decimalValue() ?: BigDecimal.ZERO,
                equipmentCount = tree.get("summary")?.get("equipmentCount")?.asInt() ?: 0,
                equipment = tree.get("equipment")?.let {
                    objectMapper.readValue(it.toString(),
                        objectMapper.typeFactory.constructCollectionType(List::class.java, Map::class.java))
                } ?: emptyList(),
                calculatedAt = Instant.parse(
                    tree.get("metadata")?.get("calculatedAt")?.asText()
                        ?: Instant.now().toString()
                ),
            )
        }
    }
}
