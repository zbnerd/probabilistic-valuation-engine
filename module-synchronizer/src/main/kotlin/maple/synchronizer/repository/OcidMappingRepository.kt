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
        private const val BATCH_SIZE = 50000
        private const val REDIS_KEY = "ocid:mapping"
        private const val MERGE_SQL = """
            INSERT INTO game_character (user_ign, ocid, created_at, updated_at)
            SELECT unnest(:userIgns::varchar[]), unnest(:ocids::varchar[]), now(), now()
            ON CONFLICT (user_ign) DO UPDATE SET
                ocid = EXCLUDED.ocid,
                updated_at = now()
            WHERE game_character.ocid IS DISTINCT FROM EXCLUDED.ocid
        """
    }

    fun batchUpsert(mappings: List<OcidMapping>) {
        val batches = mappings.chunked(BATCH_SIZE)
        batches.forEachIndexed { index, batch ->
            jdbc.update(MERGE_SQL, MapSqlParameterSource()
                .addValue("userIgns", batch.map { it.userIgn }.toTypedArray())
                .addValue("ocids", batch.map { it.ocid }.toTypedArray())
            )
            log.info("[OcidMapping] DB upsert batch {}/{}: {} mappings", index + 1, batches.size, batch.size)
        }
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
