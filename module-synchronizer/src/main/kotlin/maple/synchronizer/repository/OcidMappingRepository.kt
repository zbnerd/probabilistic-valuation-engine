package maple.synchronizer.repository

import maple.synchronizer.storage.OcidMapping
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Connection

@Repository
class OcidMappingRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val REDIS_KEY = "ocid:mapping"
    }

    fun batchUpsert(mappings: List<OcidMapping>) {
        val affected: Int = jdbc.jdbcTemplate.execute(
            ConnectionCallback { con: Connection ->
                con.autoCommit = false

                runCatching {
                    con.createStatement().use { stmt ->
                        stmt.execute("DROP TABLE IF EXISTS tmp_ocid_mapping")
                        stmt.execute("CREATE TEMP TABLE tmp_ocid_mapping (user_ign text NOT NULL, ocid text NOT NULL) ON COMMIT DROP")
                    }

                    val copyManager = CopyManager(con.unwrap(BaseConnection::class.java))
                    val data = mappings.joinToString("\n") { "${it.userIgn}\t${it.ocid}" }
                    copyManager.copyIn("COPY tmp_ocid_mapping (user_ign, ocid) FROM STDIN", data.reader())

                    val rows = con.createStatement().use { stmt ->
                        stmt.executeUpdate("""
                            DELETE FROM game_character
                            WHERE EXISTS (
                                SELECT 1 FROM tmp_ocid_mapping t
                                WHERE t.ocid = game_character.ocid AND t.user_ign != game_character.user_ign
                            )
                        """.trimIndent())
                        stmt.executeUpdate("""
                            INSERT INTO game_character (user_ign, ocid, updated_at)
                            SELECT user_ign, ocid, now()
                            FROM tmp_ocid_mapping
                            ON CONFLICT (user_ign) DO UPDATE SET
                                ocid = EXCLUDED.ocid,
                                updated_at = now()
                            WHERE game_character.ocid IS DISTINCT FROM EXCLUDED.ocid
                        """.trimIndent())
                    }

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

    fun writeOcidToRedis(mappings: List<OcidMapping>) {
        redisTemplate.delete(REDIS_KEY)
        redisTemplate.executePipelined { connection ->
            for (mapping in mappings) {
                connection.hashCommands().hSet(
                    REDIS_KEY.toByteArray(),
                    mapping.userIgn.toByteArray(),
                    mapping.ocid.toByteArray(),
                )
            }
            null
        }
        log.info("[OcidMapping] Redis written: {} mappings to {}", mappings.size, REDIS_KEY)
    }
}
