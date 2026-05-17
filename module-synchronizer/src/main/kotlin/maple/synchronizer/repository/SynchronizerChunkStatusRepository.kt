package maple.synchronizer.repository

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class SynchronizerChunkStatusRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(SynchronizerChunkStatusRepository::class.java)

    fun isAlreadySuccess(runId: String, chunkId: String): Boolean {
        val sql = """
            SELECT COUNT(*) FROM synchronizer_chunk_status
            WHERE run_id = :runId AND chunk_id = :chunkId AND status = 'SUCCESS'
        """.trimIndent()
        val count = jdbc.queryForObject(sql, params(runId, chunkId), Long::class.java)
        return count != null && count > 0
    }

    /**
     * Atomic claim: only succeeds if status is FAILED, or PROCESSING with updated_at > 15 min ago.
     * Returns true if this worker successfully claimed the chunk.
     */
    fun claimChunk(runId: String, chunkId: String, resultObjectKey: String): Boolean {
        val sql = """
            INSERT INTO synchronizer_chunk_status (
                run_id, chunk_id, result_object_key, status,
                received_at, started_at, updated_at
            ) VALUES (
                :runId, :chunkId, :resultObjectKey, 'PROCESSING',
                now(), now(), now()
            )
            ON CONFLICT (run_id, chunk_id) DO UPDATE SET
                status = 'PROCESSING',
                result_object_key = :resultObjectKey,
                started_at = now(),
                updated_at = now(),
                error_message = NULL
            WHERE synchronizer_chunk_status.status = 'FAILED'
               OR (
                   synchronizer_chunk_status.status = 'PROCESSING'
                   AND synchronizer_chunk_status.updated_at < now() - interval '15 minutes'
               )
        """.trimIndent()
        val affected = jdbc.update(sql, params(runId, chunkId, resultObjectKey))
        return affected > 0
    }

    fun markSuccess(runId: String, chunkId: String) {
        val sql = """
            UPDATE synchronizer_chunk_status
            SET status = 'SUCCESS', completed_at = now(), updated_at = now()
            WHERE run_id = :runId AND chunk_id = :chunkId
        """.trimIndent()
        jdbc.update(sql, params(runId, chunkId))
    }

    fun markFailed(runId: String, chunkId: String, errorMessage: String) {
        val sql = """
            UPDATE synchronizer_chunk_status
            SET status = 'FAILED', error_message = :errorMessage, updated_at = now()
            WHERE run_id = :runId AND chunk_id = :chunkId
        """.trimIndent()
        jdbc.update(sql, mapOf(
            "runId" to runId,
            "chunkId" to chunkId,
            "errorMessage" to errorMessage.take(2000),
        ))
        log.error("[Synchronizer] chunk failed: runId={} chunkId={} error={}", runId, chunkId, errorMessage)
    }

    private fun params(runId: String, chunkId: String, resultObjectKey: String = "") = mapOf(
        "runId" to runId,
        "chunkId" to chunkId,
        "resultObjectKey" to resultObjectKey,
    )
}
