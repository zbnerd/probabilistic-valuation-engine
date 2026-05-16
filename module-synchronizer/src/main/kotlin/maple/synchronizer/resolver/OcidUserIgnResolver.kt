package maple.synchronizer.resolver

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
class OcidUserIgnResolver(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(ocids: Set<String>): Map<String, String> {
        if (ocids.isEmpty()) return emptyMap()

        val sql = """
            SELECT ocid, user_ign FROM game_character WHERE ocid IN (:ocids)
        """.trimIndent()

        val params = MapSqlParameterSource("ocids", ocids.toList())

        val rows = jdbc.queryForList(sql, params, Map::class.java)

        val mapping = rows.associate { row ->
            row["ocid"].toString() to row["user_ign"].toString()
        }
        log.debug("Resolved {} of {} ocids to userIgn", mapping.size, ocids.size)
        return mapping
    }
}
