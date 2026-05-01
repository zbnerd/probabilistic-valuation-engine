package maple.expectation.infrastructure.persistence

import java.sql.Timestamp
import java.time.Instant
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.ExpectationReadModelRepository
import maple.expectation.util.GzipUtils
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * V5 Query Server: Write service for Character Expectation Read Model
 *
 * <p>Serializes V5 response JSON to GZIP-compressed BYTEA and writes to
 * `character_expectation_read_model` via atomic UPSERT function.
 *
 * <p><strong>Exception Handling:</strong> Uses [LogicExecutor] pattern per Zero Try-Catch policy.
 * IOException from GZIP compression is wrapped in IllegalStateException for proper propagation.
 *
 * @see ExpectationReadModelRepository
 * @see GzipUtils
 */
@Service
@ConditionalOnProperty(name = ["v5.enabled"], havingValue = "true", matchIfMissing = false)
class ExpectationReadModelWriteService(
    private val repository: ExpectationReadModelRepository,
    private val jdbc: NamedParameterJdbcTemplate,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(ExpectationReadModelWriteService::class.java)

    /**
     * Writes JSON response to read model table with GZIP compression
     *
     * @param userIgn Character name (primary key)
     * @param json JSON string to compress and store
     * @param calculatedAt Timestamp of expectation calculation
     */
    @Transactional(value = "transactionManager", readOnly = false)
    fun writeToReadModel(userIgn: String, json: String, calculatedAt: Instant) {
        val context = TaskContext.of("ReadModel", "Write", userIgn)
        executor.executeVoid({ performWrite(userIgn, json, calculatedAt) }, context)
    }

    private fun performWrite(userIgn: String, json: String, calculatedAt: Instant) {
        val compressed = GzipUtils.compressUnchecked(json)
        repository.upsertNative(userIgn, compressed, calculatedAt)
        log.debug("[ReadModel] Saved: userIgn={}, compressedSize={}", userIgn, compressed.size)
    }

    /**
     * Raw write method without executor wrapping.
     * Used internally by services that already have executor wrapping.
     *
     * @param userIgn Character name (primary key)
     * @param json JSON string to compress and store
     * @param calculatedAt Timestamp of expectation calculation
     */
    @Transactional(value = "transactionManager", readOnly = false)
    fun writeToReadModelRaw(userIgn: String, json: String, calculatedAt: Instant) {
        val compressed = GzipUtils.compressUnchecked(json)
        repository.upsertNative(userIgn, compressed, calculatedAt)
        log.debug("[ReadModel] Saved: userIgn={}, compressedSize={}", userIgn, compressed.size)
    }

    /**
     * Batch raw write method without executor wrapping.
     * Used by projection batches that already have executor/recovery wrapping.
     */
    @Transactional(value = "transactionManager", readOnly = false)
    fun writeToReadModelRawBatch(commands: List<ReadModelWriteCommand>): Int {
        if (commands.isEmpty()) return 0
        val params = commands.map { command ->
            MapSqlParameterSource()
                .addValue("userIgn", command.userIgn)
                .addValue("payload", GzipUtils.compressUnchecked(command.json))
                .addValue("calculatedAt", Timestamp.from(command.calculatedAt))
        }
        val counts = jdbc.batchUpdate(
            """
            INSERT INTO character_expectation_read_model (user_ign, payload, calculated_at, updated_at)
            VALUES (:userIgn, :payload, :calculatedAt, NOW())
            ON CONFLICT (user_ign) DO UPDATE SET
                payload = EXCLUDED.payload,
                calculated_at = EXCLUDED.calculated_at,
                updated_at = NOW()
            WHERE EXCLUDED.calculated_at >= character_expectation_read_model.calculated_at
              AND character_expectation_read_model.payload IS DISTINCT FROM EXCLUDED.payload
            """,
            params.toTypedArray(),
        )
        log.debug("[ReadModel] Batch saved: rows={}", commands.size)
        return counts.sumOf { if (it > 0) it else 0 }
    }
}

data class ReadModelWriteCommand(
    val userIgn: String,
    val json: String,
    val calculatedAt: Instant,
)
