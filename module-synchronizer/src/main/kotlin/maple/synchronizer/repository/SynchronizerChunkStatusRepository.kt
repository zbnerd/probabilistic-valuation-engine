package maple.synchronizer.repository

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class SynchronizerChunkStatusRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(SynchronizerChunkStatusRepository::class.java)

    fun markReceived(runId: String, chunkId: String, resultObjectKey: String) {
        val sql = """
            INSERT INTO synchronizer_chunk_status (run_id, chunk_id, result_object_key, status, received_at, updated_at)
            VALUES (:runId, :chunkId, :resultObjectKey, 'RECEIVED', now(), now())
            ON CONFLICT (run_id, chunk_id) DO UPDATE SET
                status = 'RECEIVED', result_object_key = :resultObjectKey,
                received_at = now(), error_message = NULL, updated_at = now()
        """.trimIndent()
        jdbc.update(sql, params(runId, chunkId, resultObjectKey))
    }

    fun markProcessing(runId: String, chunkId: String) {
        updateStatus(runId, chunkId, "PROCESSING", "started_at")
    }

    fun markStaged(runId: String, chunkId: String, documentsCount: Int, itemsCount: Long) {
        val sql = """
            UPDATE synchronizer_chunk_status
            SET status = 'STAGED', documents_count = :documentsCount, items_count = :itemsCount,
                staged_at = now(), updated_at = now()
            WHERE run_id = :runId AND chunk_id = :chunkId
        """.trimIndent()
        jdbc.update(sql, mapOf(
            "runId" to runId,
            "chunkId" to chunkId,
            "documentsCount" to documentsCount,
            "itemsCount" to itemsCount,
        ))
    }

    fun markUpserted(runId: String, chunkId: String) {
        updateStatus(runId, chunkId, "UPSERTED", "upserted_at")
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

    private fun updateStatus(runId: String, chunkId: String, status: String, timestampColumn: String) {
        val sql = """
            UPDATE synchronizer_chunk_status
            SET status = :status, $timestampColumn = now(), updated_at = now()
            WHERE run_id = :runId AND chunk_id = :chunkId
        """.trimIndent()
        jdbc.update(sql, mapOf("runId" to runId, "chunkId" to chunkId, "status" to status))
    }

    private fun params(runId: String, chunkId: String, resultObjectKey: String = "") = mapOf(
        "runId" to runId,
        "chunkId" to chunkId,
        "resultObjectKey" to resultObjectKey,
    )
}
