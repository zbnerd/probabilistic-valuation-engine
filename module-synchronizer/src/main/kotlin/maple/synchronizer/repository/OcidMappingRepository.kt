package maple.synchronizer.repository

import maple.synchronizer.storage.OcidMapping
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class OcidMappingRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mergePolicy: OcidMappingMergePolicy,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val SELECT_EXISTING_BY_OCIDS_SQL =
            "SELECT user_ign, ocid FROM game_character WHERE ocid IN (:ocids)"
        private const val DELETE_STALE_BY_OCIDS_SQL =
            "DELETE FROM game_character WHERE ocid IN (:ocids)"
        private val UPSERT_SQL = """
            INSERT INTO game_character (user_ign, ocid, updated_at)
            VALUES (:userIgn, :ocid, now())
            ON CONFLICT (user_ign) DO UPDATE SET
                ocid = EXCLUDED.ocid,
                updated_at = now()
            WHERE game_character.ocid IS DISTINCT FROM EXCLUDED.ocid
        """.trimIndent()
    }

    @Transactional
    fun batchUpsert(mappings: List<OcidMapping>) {
        val incomingOcids: List<String> = mappings.map { it.ocid }.distinct()

        val existing: List<OcidMapping> = jdbc.query(
            SELECT_EXISTING_BY_OCIDS_SQL,
            MapSqlParameterSource("ocids", incomingOcids),
        ) { rs, _ ->
            OcidMapping(
                userIgn = rs.getString("user_ign"),
                ocid = rs.getString("ocid"),
            )
        }

        val plan = mergePolicy.plan(existing, mappings)

        if (plan.ocidsToDelete.isNotEmpty()) {
            jdbc.update(
                DELETE_STALE_BY_OCIDS_SQL,
                MapSqlParameterSource("ocids", plan.ocidsToDelete),
            )
        }

        var affected = 0
        for (mapping in plan.mappingsToInsert) {
            affected += jdbc.update(
                UPSERT_SQL,
                MapSqlParameterSource()
                    .addValue("userIgn", mapping.userIgn)
                    .addValue("ocid", mapping.ocid),
            )
        }

        log.info(
            "[OcidMapping] DB upserted via policy: {} mappings, {} stale deleted, {} affected",
            mappings.size,
            plan.ocidsToDelete.size,
            affected,
        )
    }
}
