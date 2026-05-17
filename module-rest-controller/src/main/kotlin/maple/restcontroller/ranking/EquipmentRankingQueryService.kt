package maple.restcontroller.ranking

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class EquipmentRankingQueryService(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun topByTotalCost(presetNo: Int, limit: Int): List<EquipmentRankingEntry> {
        val sql = """
            SELECT user_ign, preset_no, total_cost
            FROM character_equipment_read_model
            WHERE preset_no = :presetNo
              AND user_ign IS NOT NULL
            ORDER BY total_cost DESC
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("presetNo", presetNo)
            .addValue("limit", limit)

        return jdbc.query(sql, params) { rs, rowNum ->
            EquipmentRankingEntry(
                rank = rowNum + 1,
                userIgn = rs.getString("user_ign"),
                presetNo = rs.getInt("preset_no"),
                totalCost = rs.getLong("total_cost"),
            )
        }
    }
}
