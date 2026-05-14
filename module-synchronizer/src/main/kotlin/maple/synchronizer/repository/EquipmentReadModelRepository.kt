package maple.synchronizer.repository

import maple.synchronizer.preparer.PreppedDocument
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class EquipmentReadModelRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(EquipmentReadModelRepository::class.java)

    companion object {
        private const val SUB_BATCH_SIZE = 100
    }

    fun bulkUpsert(runId: String, chunkId: String, documents: List<PreppedDocument>) {
        val batches = documents.chunked(SUB_BATCH_SIZE)
        val compSizes = documents.map { it.compressed.size }
        val totalStart = System.currentTimeMillis()

        log.info("[Synchronizer] upsert start: docs={} batches={} batchSize={} " +
            "compressedBytes avg={} max={} total={} : runId={} chunkId={}",
            documents.size, batches.size, SUB_BATCH_SIZE,
            compSizes.average().toInt(), compSizes.max(), compSizes.sum(),
            runId, chunkId)

        var totalAffected = 0
        batches.forEachIndexed { idx, batch ->
            val batchStart = System.currentTimeMillis()
            val affected = upsertBatch(runId, chunkId, batch)
            val batchMs = System.currentTimeMillis() - batchStart
            totalAffected += affected
            log.info("[Synchronizer] upsert batch: batchNo={}/{} attempted={} affected={} durationMs={}",
                idx + 1, batches.size, batch.size, affected, batchMs)
        }

        val totalMs = System.currentTimeMillis() - totalStart
        log.info("[Synchronizer] upsert done: docs={} affected={} totalDurationMs={} : runId={} chunkId={}",
            documents.size, totalAffected, totalMs, runId, chunkId)
    }

    private fun upsertBatch(runId: String, chunkId: String, batch: List<PreppedDocument>): Int {
        val sql = """
            INSERT INTO character_equipment_read_model (
                read_key, ocid, preset_no, document, document_hash,
                total_cost, equipment_count, calculated_at,
                source_run_id, source_chunk_id, updated_at
            )
            SELECT
                unnest(:readKeys), unnest(:ocids), unnest(:presetNos),
                unnest(:documents), unnest(:documentHashes),
                unnest(:totalCosts), unnest(:equipmentCounts), unnest(:calculatedAts),
                :runId, :chunkId, now()
            ON CONFLICT (read_key) DO UPDATE SET
                document = excluded.document,
                document_hash = excluded.document_hash,
                total_cost = excluded.total_cost,
                equipment_count = excluded.equipment_count,
                calculated_at = excluded.calculated_at,
                source_run_id = excluded.source_run_id,
                source_chunk_id = excluded.source_chunk_id,
                updated_at = now()
            WHERE character_equipment_read_model.document_hash IS DISTINCT FROM excluded.document_hash
        """.trimIndent()

        return jdbc.update(sql, MapSqlParameterSource()
            .addValue("runId", runId)
            .addValue("chunkId", chunkId)
            .addValue("readKeys", batch.map { it.readKey }.toTypedArray())
            .addValue("ocids", batch.map { it.ocid }.toTypedArray())
            .addValue("presetNos", batch.map { it.presetNo }.toTypedArray())
            .addValue("documents", batch.map { it.compressed }.toTypedArray())
            .addValue("documentHashes", batch.map { it.documentHash }.toTypedArray())
            .addValue("totalCosts", batch.map { it.totalCost }.toTypedArray())
            .addValue("equipmentCounts", batch.map { it.equipmentCount }.toTypedArray())
            .addValue("calculatedAts", batch.map { it.calculatedAt }.toTypedArray())
        )
    }
}
