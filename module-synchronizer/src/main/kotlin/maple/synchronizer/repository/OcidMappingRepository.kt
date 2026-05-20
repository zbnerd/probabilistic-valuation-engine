package maple.synchronizer.repository

import maple.synchronizer.storage.OcidMapping
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class OcidMappingRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BATCH_SIZE = 1000
        private const val REDIS_KEY = "ocid:mapping"
    }

    fun batchUpsert(mappings: List<OcidMapping>) {
        var upserted = 0
        mappings.chunked(BATCH_SIZE).forEach { batch ->
            val sql = """
                INSERT INTO game_character (user_ign, ocid, updated_at)
                SELECT unnest(:userIgns::varchar[]), unnest(:ocids::varchar[]), now()
                ON CONFLICT (user_ign) DO UPDATE SET
                    ocid = EXCLUDED.ocid,
                    updated_at = EXCLUDED.updated_at
                WHERE game_character.ocid IS DISTINCT FROM EXCLUDED.ocid
            """.trimIndent()

            jdbc.update(sql, MapSqlParameterSource()
                .addValue("userIgns", batch.map { it.userIgn }.toTypedArray())
                .addValue("ocids", batch.map { it.ocid }.toTypedArray())
            )
            upserted += batch.size
        }
        log.info("[OcidMapping] DB upserted: {} mappings", upserted)
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
