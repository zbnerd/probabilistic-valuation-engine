package maple.expectation.infrastructure.persistence.repository

import java.time.Instant
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class CharacterViewBatchRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
) {
    companion object {
        private val log = LoggerFactory.getLogger(CharacterViewBatchRepository::class.java)

        private val SELECT_EXISTING_SQL = """
            SELECT DISTINCT ON (user_ign)
                id, user_ign, version, last_applied_version
            FROM character_valuation_views
            WHERE user_ign = ANY(?)
            ORDER BY user_ign, calculated_at DESC, id DESC
        """.trimIndent()

        private val UPDATE_SQL = """
            UPDATE character_valuation_views SET
                message_id = ?, character_ocid = ?, character_class = ?,
                calculated_at = ?, jpa_version = COALESCE(jpa_version, 0) + 1,
                version = version + 1, last_applied_version = ?,
                total_expected_cost = ?, max_preset_no = ?,
                presets = ?::jsonb, from_cache = ?
            WHERE id = ? AND COALESCE(last_applied_version, version, 0) < ?
        """.trimIndent()

        private val INSERT_SQL = """
            INSERT INTO character_valuation_views (
                user_ign, message_id, character_ocid, character_class,
                calculated_at, version, last_applied_version,
                total_expected_cost, max_preset_no, presets, from_cache, jpa_version
            ) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?::jsonb, ?, 0)
        """.trimIndent()
    }

    data class ParsedViewResult(
        val userIgn: String,
        val messageId: String,
        val characterOcid: String,
        val characterClass: String,
        val totalExpectedCost: Long,
        val maxPresetNo: Int,
        val presetsJson: String,
        val version: Long,
    )

    private data class ExistingRow(
        val id: Long,
        val version: Long?,
        val lastAppliedVersion: Long?,
    )

    private data class UpdateEntry(
        val result: ParsedViewResult,
        val existingId: Long,
    )

    fun bulkUpsert(results: List<ParsedViewResult>) {
        if (results.isEmpty()) return

        val context = TaskContext.of("ViewBatch", "BulkUpsert", "${results.size}")
        executor.executeVoid({
            val existing = findExistingByUserIgns(results.map { it.userIgn }.toSet())
            val (toUpdate, toInsert) = splitUpdateInsert(results, existing)

            val updated = if (toUpdate.isNotEmpty()) batchUpdate(toUpdate) else 0
            val inserted = if (toInsert.isNotEmpty()) batchInsert(toInsert) else 0

            log.info("[ViewBatch] Bulk upsert: {} results -> {} updated, {} inserted", results.size, updated, inserted)
        }, context)
    }

    private fun findExistingByUserIgns(userIgns: Set<String>): Map<String, ExistingRow> {
        val existing = mutableMapOf<String, ExistingRow>()
        jdbcTemplate.query(SELECT_EXISTING_SQL, { rs ->
            existing[rs.getString("user_ign")] = ExistingRow(
                id = rs.getLong("id"),
                version = rs.getLong("version").takeIf { !rs.wasNull() },
                lastAppliedVersion = rs.getLong("last_applied_version").takeIf { !rs.wasNull() },
            )
        }, userIgns.toTypedArray())
        return existing
    }

    private fun splitUpdateInsert(
        results: List<ParsedViewResult>,
        existing: Map<String, ExistingRow>,
    ): Pair<List<UpdateEntry>, List<ParsedViewResult>> {
        val toUpdate = mutableListOf<UpdateEntry>()
        val toInsert = mutableListOf<ParsedViewResult>()

        for (result in results) {
            val row = existing[result.userIgn]
            if (row == null) {
                toInsert.add(result)
            } else {
                val currentVersion = row.lastAppliedVersion ?: row.version ?: 0L
                if (result.version > currentVersion) {
                    toUpdate.add(UpdateEntry(result, row.id))
                }
            }
        }
        return toUpdate to toInsert
    }

    private fun batchUpdate(entries: List<UpdateEntry>): Int {
        val now = Instant.now()
        val args = entries.map { e ->
            arrayOf<Any?>(
                e.result.messageId,
                e.result.characterOcid,
                e.result.characterClass,
                now,
                e.result.version,
                e.result.totalExpectedCost,
                e.result.maxPresetNo,
                e.result.presetsJson,
                false,
                e.existingId,
                e.result.version,
            )
        }
        return jdbcTemplate.batchUpdate(UPDATE_SQL, args).sumOf { it }
    }

    private fun batchInsert(rows: List<ParsedViewResult>): Int {
        val now = Instant.now()
        val args = rows.map { r ->
            arrayOf<Any?>(
                r.userIgn,
                r.messageId,
                r.characterOcid,
                r.characterClass,
                now,
                r.version,
                r.totalExpectedCost,
                r.maxPresetNo,
                r.presetsJson,
                false,
            )
        }
        return jdbcTemplate.batchUpdate(INSERT_SQL, args).sumOf { it }
    }
}
