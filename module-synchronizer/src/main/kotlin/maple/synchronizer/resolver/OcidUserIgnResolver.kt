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

        val mapping = jdbc.queryForList(sql, params)
            .associate { row ->
                row["ocid"].toString() to (row["user_ign"]?.toString() ?: "")
            }
            .filterValues { it.isNotEmpty() }
        log.debug("Resolved {} of {} ocids to userIgn", mapping.size, ocids.size)
        return mapping
    }
}
