package maple.synchronizer.repository

import maple.expectation.common.event.ChunkExecutionIdentity
import maple.synchronizer.state.ChunkExecutionStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

data class InsertChunkExecutionCommand(
    val identity: ChunkExecutionIdentity,
    val topic: String,
    val messageKey: String,
    val eventType: String,
    val schemaVersion: Int,
    val eventPayloadJson: String,
)

data class ChunkExecutionClaim(
    val attemptCount: Int,
)

data class ChunkExecutionState(
    val status: ChunkExecutionStatus,
    val nextRetryAt: Instant?,
    val leaseUntil: Instant?,
    val attemptCount: Int,
)

@Repository
class ChunkExecutionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun insertPendingIfAbsent(command: InsertChunkExecutionCommand): Boolean {
        val sql = """
            INSERT INTO chunk_execution (
                execution_type,
                run_id,
                endpoint,
                chunk_id,
                topic,
                message_key,
                event_type,
                schema_version,
                event_payload_jsonb,
                status,
                attempt_count
            ) VALUES (
                :executionType,
                :runId,
                :endpoint,
                :chunkId,
                :topic,
                :messageKey,
                :eventType,
                :schemaVersion,
                CAST(:eventPayloadJson AS jsonb),
                :status,
                :attemptCount
            )
            ON CONFLICT (execution_type, run_id, endpoint, chunk_id) DO NOTHING
        """.trimIndent()

        return jdbc.update(sql, command.toParams()) > 0
    }

    fun findStatus(identity: ChunkExecutionIdentity): ChunkExecutionStatus? {
        val sql = """
            SELECT status
            FROM chunk_execution
            WHERE execution_type = :executionType
              AND run_id = :runId
              AND endpoint = :endpoint
              AND chunk_id = :chunkId
        """.trimIndent()

        return jdbc.query(sql, identity.toParams()) { rs, _ -> rs.getString("status") }
            .firstOrNull()
            ?.let(ChunkExecutionStatus::fromName)
    }

    fun findExecutionState(identity: ChunkExecutionIdentity): ChunkExecutionState? {
        val sql = """
            SELECT
                status,
                next_retry_at,
                lease_until,
                attempt_count
            FROM chunk_execution
            WHERE execution_type = :executionType
              AND run_id = :runId
              AND endpoint = :endpoint
              AND chunk_id = :chunkId
        """.trimIndent()

        return jdbc.query(sql, identity.toParams()) { rs, _ ->
            val statusName = rs.getString("status")
            val nextRetryAt = rs.getTimestamp("next_retry_at")?.toInstant()
            val status: ChunkExecutionStatus = when (statusName) {
                ChunkExecutionStatus.FAILED_RETRYABLE_NAME -> ChunkExecutionStatus.FailedRetryable(nextRetryAt)
                else -> ChunkExecutionStatus.fromName(statusName)
            }
            ChunkExecutionState(
                status = status,
                nextRetryAt = nextRetryAt,
                leaseUntil = rs.getTimestamp("lease_until")?.toInstant(),
                attemptCount = rs.getInt("attempt_count"),
            )
        }.firstOrNull()
    }

    fun claimProcessing(identity: ChunkExecutionIdentity, processingTimeout: Duration): ChunkExecutionClaim? {
        val sql = """
            UPDATE chunk_execution
            SET
              status = 'PROCESSING',
              attempt_count = attempt_count + 1,
              processing_started_at = now(),
              lease_until = now() + (:processingTimeoutSeconds * interval '1 second'),
              updated_at = now()
            WHERE execution_type = :executionType
              AND run_id = :runId
              AND endpoint = :endpoint
              AND chunk_id = :chunkId
              AND (
                status = 'PENDING'
                OR (status = 'FAILED_RETRYABLE' AND (next_retry_at IS NULL OR next_retry_at <= now()))
                OR (status = 'PROCESSING' AND lease_until < now())
              )
            RETURNING attempt_count
        """.trimIndent()

        val params = identity.toParams()
            .addValue("processingTimeoutSeconds", processingTimeout.seconds)

        return jdbc.query(sql, params) { rs, _ -> ChunkExecutionClaim(rs.getInt("attempt_count")) }
            .firstOrNull()
    }

    fun markSucceeded(identity: ChunkExecutionIdentity, claimedAttempt: Int): Boolean {
        val sql = """
            UPDATE chunk_execution
            SET
              status = 'SUCCEEDED',
              next_retry_at = NULL,
              last_error = NULL,
              terminal_reason = NULL,
              lease_until = NULL,
              updated_at = now()
            WHERE execution_type = :executionType
              AND run_id = :runId
              AND endpoint = :endpoint
              AND chunk_id = :chunkId
              AND status = 'PROCESSING'
              AND attempt_count = :claimedAttempt
        """.trimIndent()

        return jdbc.update(sql, identity.toParams().addValue("claimedAttempt", claimedAttempt)) > 0
    }

    fun markFailedRetryable(
        identity: ChunkExecutionIdentity,
        claimedAttempt: Int,
        error: String,
        nextRetryAt: Instant,
    ): Boolean {
        val sql = """
            UPDATE chunk_execution
            SET
              status = 'FAILED_RETRYABLE',
              next_retry_at = :nextRetryAt,
              first_failed_at = COALESCE(first_failed_at, now()),
              last_failed_at = now(),
              last_error = :error,
              terminal_reason = NULL,
              lease_until = NULL,
              updated_at = now()
            WHERE execution_type = :executionType
              AND run_id = :runId
              AND endpoint = :endpoint
              AND chunk_id = :chunkId
              AND status = 'PROCESSING'
              AND attempt_count = :claimedAttempt
        """.trimIndent()

        return jdbc.update(
            sql,
            identity.toParams()
                .addValue("claimedAttempt", claimedAttempt)
                .addValue("nextRetryAt", Timestamp.from(nextRetryAt))
                .addValue("error", error.safeError()),
        ) > 0
    }

    fun markFailedTerminal(
        identity: ChunkExecutionIdentity,
        claimedAttempt: Int,
        error: String,
        terminalReason: String,
    ): Boolean {
        val sql = """
            UPDATE chunk_execution
            SET
              status = 'FAILED_TERMINAL',
              terminal_reason = :terminalReason,
              next_retry_at = NULL,
              first_failed_at = COALESCE(first_failed_at, now()),
              last_failed_at = now(),
              last_error = :error,
              lease_until = NULL,
              updated_at = now()
            WHERE execution_type = :executionType
              AND run_id = :runId
              AND endpoint = :endpoint
              AND chunk_id = :chunkId
              AND status = 'PROCESSING'
              AND attempt_count = :claimedAttempt
        """.trimIndent()

        return jdbc.update(
            sql,
            identity.toParams()
                .addValue("claimedAttempt", claimedAttempt)
                .addValue("terminalReason", terminalReason)
                .addValue("error", error.safeError()),
        ) > 0
    }

    private fun InsertChunkExecutionCommand.toParams(): MapSqlParameterSource =
        identity.toParams()
            .addValue("topic", topic)
            .addValue("messageKey", messageKey)
            .addValue("eventType", eventType)
            .addValue("schemaVersion", schemaVersion)
            .addValue("eventPayloadJson", eventPayloadJson)
            .addValue("status", ChunkExecutionStatus.PENDING_NAME)
            .addValue("attemptCount", 0)

    private fun ChunkExecutionIdentity.toParams(): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("executionType", executionType.name)
            .addValue("runId", runId)
            .addValue("endpoint", endpoint)
            .addValue("chunkId", chunkId)

    private fun String.safeError(): String = take(MAX_ERROR_LENGTH)

    private companion object {
        private const val MAX_ERROR_LENGTH = 2_000
    }
}
