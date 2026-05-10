package maple.synchronizer.repository

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import maple.synchronizer.domain.EquipmentReadDocument
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp

@Repository
class EquipmentReadModelRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(EquipmentReadModelRepository::class.java)

    fun bulkUpsert(runId: String, chunkId: String, documents: List<EquipmentReadDocument>) {
        val sql = """
            INSERT INTO character_equipment_read_model (
                read_key, ocid, preset_no, document, total_cost, equipment_count,
                calculated_at, source_run_id, source_chunk_id, updated_at
            )
            SELECT
                unnest(:readKeys), unnest(:ocids), unnest(:presetNos),
                unnest(:documents), unnest(:totalCosts), unnest(:equipmentCounts),
                unnest(:calculatedAts), :runId, :chunkId, now()
            ON CONFLICT (read_key) DO UPDATE SET
                document = excluded.document,
                total_cost = excluded.total_cost,
                equipment_count = excluded.equipment_count,
                calculated_at = excluded.calculated_at,
                source_run_id = excluded.source_run_id,
                source_chunk_id = excluded.source_chunk_id,
                updated_at = now()
        """.trimIndent()

        jdbc.update(sql, MapSqlParameterSource()
            .addValue("runId", runId)
            .addValue("chunkId", chunkId)
            .addValue("readKeys", documents.map { "${it.ocid}:${it.presetNo}" }.toTypedArray())
            .addValue("ocids", documents.map { it.ocid }.toTypedArray())
            .addValue("presetNos", documents.map { it.presetNo.toShort() }.toTypedArray())
            .addValue("documents", documents.map { GzipUtils.compress(objectMapper.writeValueAsString(it)) }.toTypedArray())
            .addValue("totalCosts", documents.map { it.summary.totalCost }.toTypedArray())
            .addValue("equipmentCounts", documents.map { it.summary.equipmentCount }.toTypedArray())
            .addValue("calculatedAts", documents.map { Timestamp.from(it.metadata.calculatedAt) }.toTypedArray())
        )
        log.info("[Synchronizer] upserted {} documents: runId={} chunkId={}", documents.size, runId, chunkId)
    }
}
