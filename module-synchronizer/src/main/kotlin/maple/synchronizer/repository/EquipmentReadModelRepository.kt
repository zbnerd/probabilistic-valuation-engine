package maple.synchronizer.repository

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import maple.synchronizer.domain.EquipmentReadDocument
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.sql.Timestamp

@Repository
class EquipmentReadModelRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(EquipmentReadModelRepository::class.java)

    companion object {
        private const val SUB_BATCH_SIZE = 100
    }

    fun bulkUpsert(runId: String, chunkId: String, documents: List<EquipmentReadDocument>) {
        val prepped = documents.map { doc ->
            val json = objectMapper.writeValueAsString(doc)
            PreppedDocument(
                readKey = "${doc.ocid}:${doc.presetNo}",
                ocid = doc.ocid,
                presetNo = doc.presetNo.toShort(),
                compressed = GzipUtils.compress(json),
                documentHash = sha256Hex(json),
                totalCost = doc.summary.totalCost,
                equipmentCount = doc.summary.equipmentCount,
                calculatedAt = Timestamp.from(doc.metadata.calculatedAt),
            )
        }

        val compSizes = prepped.map { it.compressed.size }
        val batches = prepped.chunked(SUB_BATCH_SIZE)
        val totalStart = System.currentTimeMillis()

        log.info("[Synchronizer] upsert start: docs={} batches={} batchSize={} " +
            "compressedBytes avg={} max={} total={} : runId={} chunkId={}",
            prepped.size, batches.size, SUB_BATCH_SIZE,
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
            prepped.size, totalAffected, totalMs, runId, chunkId)
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

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class PreppedDocument(
        val readKey: String,
        val ocid: String,
        val presetNo: Short,
        val compressed: ByteArray,
        val documentHash: String,
        val totalCost: java.math.BigDecimal,
        val equipmentCount: Int,
        val calculatedAt: Timestamp,
    )
}
