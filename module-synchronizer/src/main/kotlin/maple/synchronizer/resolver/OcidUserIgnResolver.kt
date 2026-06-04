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
            SELECT ocid, user_ign FROM character_basic_read_model WHERE ocid IN (:ocids)
        """.trimIndent()

        val params = MapSqlParameterSource("ocids", ocids.toList())

        val rows = jdbc.queryForList(sql, params)
        val mapping = rows.associate { row ->
            row["ocid"].toString() to (row["user_ign"]?.toString() ?: "")
        }
        val excluded = mapping.filterValues { it.isEmpty() }
        val result = mapping.filterValues { it.isNotEmpty() }
        if (excluded.isNotEmpty()) {
            log.debug("Resolved {} of {} ocids; excluded {} with empty user_ign (sample: {})",
                result.size, ocids.size, excluded.size, excluded.keys.take(5))
        } else {
            log.debug("Resolved {} of {} ocids to userIgn", result.size, ocids.size)
        }
        return result
    }
}
