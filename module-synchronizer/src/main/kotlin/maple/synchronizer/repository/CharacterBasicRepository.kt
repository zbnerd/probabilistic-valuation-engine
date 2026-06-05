package maple.synchronizer.repository

import maple.synchronizer.storage.BasicRecord
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class CharacterBasicRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val batchExecutor: JdbcChunkedBatchExecutor,
) {
    companion object {
        private const val SUB_BATCH_SIZE = 100
        private const val DELETE_STALE_SQL =
            "DELETE FROM character_basic_read_model WHERE ocid = ANY(:ocids) AND NOT (user_ign = ANY(:userIgns))"
        private val UPSERT_SQL = """
            INSERT INTO character_basic_read_model (
                user_ign, ocid, world_name, character_class, character_level,
                guild_name, basic_data, document_hash, source_run_id, source_chunk_id, updated_at
            )
            SELECT
                unnest(:userIgns), unnest(:ocids), unnest(:worldNames),
                unnest(:characterClasses), unnest(:characterLevels),
                unnest(:guildNames), unnest(:basicData), unnest(:documentHashes),
                :runId, :chunkId, now()
            ON CONFLICT (user_ign) DO UPDATE SET
                ocid = excluded.ocid,
                world_name = excluded.world_name,
                character_class = excluded.character_class,
                character_level = excluded.character_level,
                guild_name = excluded.guild_name,
                basic_data = excluded.basic_data,
                document_hash = excluded.document_hash,
                source_run_id = excluded.source_run_id,
                source_chunk_id = excluded.source_chunk_id,
                updated_at = now()
            WHERE character_basic_read_model.document_hash IS DISTINCT FROM excluded.document_hash
        """.trimIndent()
    }

    fun bulkUpsert(runId: String, chunkId: String, records: List<BasicRecord>) {
        val deduped = records
            .groupBy { it.ocid }
            .map { it.value.first() }

        batchExecutor.execute(
            label = "CharacterBasic",
            itemLabel = "records",
            runId = runId,
            chunkId = chunkId,
            items = deduped,
            batchSize = SUB_BATCH_SIZE,
            upsertBatch = { batch -> upsertBatch(runId, chunkId, batch) },
        )
    }

    private fun upsertBatch(runId: String, chunkId: String, batch: List<BasicRecord>): Int {
        val ocids = batch.map { it.ocid }.toTypedArray()
        val userIgns = batch.map { it.userIgn }.toTypedArray()

        jdbc.update(
            DELETE_STALE_SQL,
            MapSqlParameterSource()
                .addValue("ocids", ocids)
                .addValue("userIgns", userIgns),
        )

        return jdbc.update(UPSERT_SQL, buildUpsertParams(runId, chunkId, batch))
    }

    private fun buildUpsertParams(runId: String, chunkId: String, batch: List<BasicRecord>): MapSqlParameterSource {
        return MapSqlParameterSource()
            .addValue("runId", runId)
            .addValue("chunkId", chunkId)
            .addValue("userIgns", batch.map { it.userIgn }.toTypedArray())
            .addValue("ocids", batch.map { it.ocid }.toTypedArray())
            .addValue("worldNames", batch.map { it.worldName }.toTypedArray())
            .addValue("characterClasses", batch.map { it.characterClass }.toTypedArray())
            .addValue("characterLevels", batch.map { it.characterLevel }.toTypedArray())
            .addValue("guildNames", batch.map { it.guildName }.toTypedArray())
            .addValue("basicData", batch.map { it.compressedBody }.toTypedArray())
            .addValue("documentHashes", batch.map { it.bodyHash }.toTypedArray())
    }
}
