package maple.restcontroller.read

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource

object ReadModelRowQuery {
    fun build(requests: Map<String, Int>): Pair<String, MapSqlParameterSource> {
        require(requests.isNotEmpty()) { "ReadModelRowQuery.build requires non-empty requests" }
        val params = MapSqlParameterSource()
        val predicates = requests.entries.mapIndexed { i, (userIgn, presetNo) ->
            params
                .addValue("userIgn$i", userIgn)
                .addValue("presetNo$i", presetNo)
            "(user_ign = :userIgn$i AND preset_no = :presetNo$i)"
        }.joinToString(" OR ")

        val sql = """
            SELECT user_ign, preset_no, document, total_cost, equipment_count, calculated_at, updated_at
            FROM character_equipment_read_model
            WHERE ($predicates)
              AND user_ign IS NOT NULL
        """.trimIndent()
        return sql to params
    }
}
