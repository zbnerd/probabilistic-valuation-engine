package maple.restcontroller.read

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
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
    fun batchQuery(
        requests: Map<String, Int>,
        maxAge: Duration? = null,
    ): Map<String, V6ExpectationResponse> {
        if (requests.isEmpty()) return emptyMap()

        val params = MapSqlParameterSource()
        val pairPredicates = requests.entries.mapIndexed { index, (userIgn, presetNo) ->
            params
                .addValue("userIgn$index", userIgn)
                .addValue("presetNo$index", presetNo)
            "(user_ign = :userIgn$index AND preset_no = :presetNo$index)"
        }.joinToString(" OR ")

        val sql = """
            SELECT user_ign, preset_no, document, total_cost, equipment_count, calculated_at, updated_at
            FROM character_equipment_read_model
            WHERE ($pairPredicates)
              AND user_ign IS NOT NULL
        """.trimIndent()

        val rows = jdbc.queryForList(sql, params)
        val result = LinkedHashMap<String, V6ExpectationResponse>()
        val minimumUpdatedAt = maxAge?.let { Instant.now().minus(it) }
        var stale = 0
        rows.forEach { row ->
            val updatedAt = (row["updated_at"] as? Timestamp)?.toInstant() ?: Instant.EPOCH
            if (minimumUpdatedAt != null && updatedAt.isBefore(minimumUpdatedAt)) {
                stale++
                return@forEach
            }

            val userIgn = row["user_ign"].toString()
            val compressed = row["document"] as ByteArray
            val json = GzipUtils.decompress(compressed)
            val tree = objectMapper.readTree(json)
            val equipmentNode = tree["equipment"]
            val equipment = if (equipmentNode != null && !equipmentNode.isNull) {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(
                    equipmentNode.toString(),
                    objectMapper.typeFactory.constructCollectionType(List::class.java, Map::class.java),
                ) as List<Map<String, Any?>>
            } else {
                emptyList()
            }

            result[userIgn] = V6ExpectationResponse(
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
        log.debug("Read model query: requested={}, hits={}, stale={}", requests.size, result.size, stale)
        return result
    }
}
