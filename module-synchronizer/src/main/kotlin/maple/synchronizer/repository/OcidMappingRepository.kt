package maple.synchronizer.repository

import maple.synchronizer.storage.OcidMapping
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Connection

@Repository
class OcidMappingRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DROP_TMP_SQL = "DROP TABLE IF EXISTS tmp_ocid_mapping"
        private const val CREATE_TMP_SQL = "CREATE TEMP TABLE tmp_ocid_mapping (user_ign text NOT NULL, ocid text NOT NULL) ON COMMIT DROP"
        private val DELETE_CONFLICT_SQL = """
            DELETE FROM game_character
            WHERE EXISTS (
                SELECT 1 FROM tmp_ocid_mapping t
                WHERE t.ocid = game_character.ocid AND t.user_ign != game_character.user_ign
            )
        """.trimIndent()
        private val MERGE_SQL = """
            INSERT INTO game_character (user_ign, ocid, updated_at)
            SELECT user_ign, ocid, now()
            FROM tmp_ocid_mapping
            ON CONFLICT (user_ign) DO UPDATE SET
                ocid = EXCLUDED.ocid,
                updated_at = now()
            WHERE game_character.ocid IS DISTINCT FROM EXCLUDED.ocid
        """.trimIndent()
    }

    fun batchUpsert(mappings: List<OcidMapping>) {
        val affected: Int = jdbc.jdbcTemplate.execute(
            ConnectionCallback { con: Connection ->
                con.autoCommit = false
                runCatching {
                    createTempTable(con)
                    copyToTemp(con, mappings)
                    val rows = mergeFromTemp(con)
                    con.commit()
                    rows
                }.getOrElse { ex ->
                    runCatching { con.rollback() }
                    throw ex
                }
            }
        ) ?: 0

        log.info("[OcidMapping] DB upserted via COPY→merge: {} mappings, {} affected", mappings.size, affected)
    }

    private fun createTempTable(con: Connection) {
        con.createStatement().use { stmt ->
            stmt.execute(DROP_TMP_SQL)
            stmt.execute(CREATE_TMP_SQL)
        }
    }

    private fun copyToTemp(con: Connection, mappings: List<OcidMapping>) {
        val copyManager = CopyManager(con.unwrap(BaseConnection::class.java))
        val data = mappings.joinToString("\n") { "${it.userIgn}\t${it.ocid}" }
        copyManager.copyIn("COPY tmp_ocid_mapping (user_ign, ocid) FROM STDIN", data.reader())
    }

    private fun mergeFromTemp(con: Connection): Int {
        return con.createStatement().use { stmt ->
            stmt.executeUpdate(DELETE_CONFLICT_SQL)
            stmt.executeUpdate(MERGE_SQL)
        }
    }
}
