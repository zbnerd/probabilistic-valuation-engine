package maple.expectation.infrastructure.pgmq

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import java.sql.ResultSet
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.SystemException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import org.postgresql.util.PGobject
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * PGMQ 클라이언트 (ADR-002)
 *
 * <h3>역할</h3>
 * <p>PostgreSQL Message Queue(PGMQ) 작업을 위한 핵심 클라이언트
 *
 * <h3>주요 기능</h3>
 * <ul>
 *   <li>send: 메시지 발행 (트랜잭션 내 호출 가능)
 *   <li>read: 메시지 소비 (SKIP LOCKED 기반)
 *   <li>archive: 처리 완료 메시지 보관
 *   <li>delete: 메시지 삭제 (DLQ용)
 * </ul>
 *
 * <h3>Circuit Breaker</h3>
 * <p>모든 작업에 Circuit Breaker 적용으로 PostgreSQL 장애 시 빠른 실패
 *
 * <h3>Zero Try-Catch</h3>
 * <p>LogicExecutor를 통해 예외 처리 위임 (Section 12 준수)
 *
 * @see PgmqMessage 메시지 래퍼
 * @see PgmqConfig 설정
 */
@Component
class PgmqClient(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val config: PgmqConfig,
) {

    /**
     * 메시지 발행
     *
     * <p>트랜잭션 내에서 호출 가능. 동일 트랜잭션에서 DB 작업과 메시지 발행이 원자적으로 처리됨.
     *
     * @param queueName 큐 이름
     * @param message 메시지 객체 (JSON으로 직렬화)
     * @return 메시지 ID
     * @throws PgmqPublishException 발행 실패 시
     */
    @CircuitBreaker(name = "pgmq", fallbackMethod = "sendFallback")
    fun <T : Any> send(queueName: String, message: T): Long {
        // TX 활성 검증 — 반드시 send() 내부에서 수행 (AOP는 self-invocation/람다에서 우회 가능)
        if (config.transactionCheckEnabled && !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw PgmqPublishException(
                "pgmqClient.send('$queueName') must be called within @Transactional. " +
                    "Ensure the calling Service method has @Transactional annotation.",
            )
        }
        val context = TaskContext.of("PgmqClient", "Send", queueName)
        val translator = ExceptionTranslator { e, _ ->
            PgmqPublishException("Failed to send message to queue: $queueName", e)
        }
        return executor.executeWithTranslation({ performSend(queueName, message) }, translator, context)
    }

    /**
     * Raw JSON 메시지 발행 (DLQ Replay 전용)
     *
     * <p>이미 직렬화된 JSON 문자열을 그대로 JSONB로 전송.
     * Jackson 재직렬화로 인한 double-encoding 방지.
     *
     * @param queueName 큐 이름
     * @param json 직렬화된 JSON 문자열
     * @return 메시지 ID
     */
    @CircuitBreaker(name = "pgmq", fallbackMethod = "sendRawJsonFallback")
    fun sendRawJson(queueName: String, json: String): Long {
        if (config.transactionCheckEnabled && !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw PgmqPublishException(
                "pgmqClient.sendRawJson('$queueName') must be called within @Transactional.",
            )
        }
        val context = TaskContext.of("PgmqClient", "SendRaw", queueName)
        val translator = ExceptionTranslator { e, _ ->
            PgmqPublishException("Failed to send raw JSON to queue: $queueName", e)
        }
        return executor.executeWithTranslation({ performSendRawJson(queueName, json) }, translator, context)
    }

    /**
     * 메시지 소비
     *
     * <p>SKIP LOCKED 기반으로 여러 Worker가 동시에 안전하게 소비.
     * Visibility Timeout 동안 다른 Worker가 동일 메시지를 읽지 않음.
     *
     * @param queueName 큐 이름
     * @param clazz 메시지 페이로드 클래스
     * @param batchSize 한 번에 읽을 메시지 수
     * @param visibilityTimeoutSec VT (초)
     * @return 읽은 메시지 목록 (없으면 빈 리스트)
     * @throws PgmqReadException 읽기 실패 시
     */
    @CircuitBreaker(name = "pgmq", fallbackMethod = "readFallback")
    fun <T : Any> read(
        queueName: String,
        clazz: Class<T>,
        batchSize: Int = config.defaultBatchSize,
        visibilityTimeoutSec: Int = config.defaultVisibilityTimeout,
    ): List<PgmqMessage<T>> {
        val context = TaskContext.of("PgmqClient", "Read", queueName)
        val translator = ExceptionTranslator { e, _ ->
            PgmqReadException("Failed to read messages from queue: $queueName", e)
        }
        return executor.executeWithTranslation(
            { performRead(queueName, clazz, batchSize, visibilityTimeoutSec) },
            translator,
            context,
        )
    }

    /**
     * 메시지 보관 (처리 완료 표시)
     *
     * <p>처리 완료된 메시지를 아카이브 테이블로 이동.
     * 보관된 메시지는 pgmq.a_<queue_name> 테이블에서 조회 가능.
     *
     * @param queueName 큐 이름
     * @param messageId 메시지 ID
     * @return 성공 여부
     */
    @CircuitBreaker(name = "pgmq", fallbackMethod = "archiveFallback")
    fun archive(queueName: String, messageId: Long): Boolean {
        val context = TaskContext.of("PgmqClient", "Archive", "$queueName:$messageId")
        val translator = ExceptionTranslator { e, _ ->
            PgmqArchiveException("Failed to archive message: $messageId", e)
        }
        return executor.executeWithTranslation({ performArchive(queueName, messageId) }, translator, context)
    }

    /**
     * 메시지 삭제
     *
     * <p>메시지를 완전히 삭제 (DLQ 이동용).
     * 일반적으로는 archive()를 권장하며, delete()는 재처리 불가능한 에러 시에만 사용.
     *
     * @param queueName 큐 이름
     * @param messageId 메시지 ID
     * @return 성공 여부
     */
    @CircuitBreaker(name = "pgmq", fallbackMethod = "deleteFallback")
    fun delete(queueName: String, messageId: Long): Boolean {
        val context = TaskContext.of("PgmqClient", "Delete", "$queueName:$messageId")
        val translator = ExceptionTranslator { e, _ ->
            PgmqDeleteException("Failed to delete message: $messageId", e)
        }
        return executor.executeWithTranslation({ performDelete(queueName, messageId) }, translator, context)
    }

    /**
     * 메시지 Visibility Timeout 변경
     *
     * <p>이미 읽은 메시지의 VT를 변경. 429 Rate Limit 시 짧은 지연 후 재시도에 사용.
     *
     * @param queueName 큐 이름
     * @param messageId 메시지 ID
     * @param visibilityTimeoutSec 새 VT (초)
     * @return 성공 여부
     */
    @CircuitBreaker(name = "pgmq", fallbackMethod = "setVisibilityTimeoutFallback")
    fun setVisibilityTimeout(queueName: String, messageId: Long, visibilityTimeoutSec: Long): Boolean {
        val context = TaskContext.of("PgmqClient", "SetVT", "$queueName:$messageId")
        return executor.executeOrDefault(
            { performSetVisibilityTimeout(queueName, messageId, visibilityTimeoutSec) },
            false,
            context,
        )
    }

    /**
     * 큐 길이 조회
     *
     * <p>지정된 큐의 현재 대기 중인 메시지 수를 반환.
     * Backpressure 체크에 사용.
     *
     * @param queueName 큐 이름
     * @return 대기 중인 메시지 수
     */
    fun queueLength(queueName: String): Long {
        val context = TaskContext.of("PgmqClient", "QueueLength", queueName)
        return executor.executeOrDefault({ performQueueLength(queueName) }, 0L, context)
    }

    /**
     * 메시지 archive 존재 여부 확인 (ADR-355)
     *
     * <p>TaskStatusService에서 COMPLETED 판별에 사용.
     *
     * @param queueName 큐 이름
     * @param messageId 메시지 ID
     * @return archive 테이블에 존재하면 true
     */
    fun isArchived(queueName: String, messageId: Long): Boolean {
        val context = TaskContext.of("PgmqClient", "IsArchived", "$queueName:$messageId")
        return executor.executeOrDefault({ performIsArchived(queueName, messageId) }, false, context)
    }

    /**
     * 메시지 read count 조회 (ADR-355)
     *
     * <p>TaskStatusService에서 PROCESSING 판별에 사용.
     * read_ct > 0 → worker가 메시지를 소비한 적 있음 (PROCESSING).
     *
     * @param queueName 큐 이름
     * @param messageId 메시지 ID
     * @return read count (큐에 없으면 0)
     */
    fun getMessageReadCount(queueName: String, messageId: Long): Int {
        val context = TaskContext.of("PgmqClient", "ReadCount", "$queueName:$messageId")
        return executor.executeOrDefault({ performGetReadCount(queueName, messageId) }, 0, context)
    }

    /**
     * Find the latest active message for a user in the target queue.
     *
     * <p>Used to deduplicate repeated V5 MISS requests so the API can return the
     * currently active task instead of enqueueing duplicate work.
     */
    fun findActiveMessageIdByUserIgn(queueName: String, userIgn: String): Long? {
        val context = TaskContext.of("PgmqClient", "FindActiveMessage", "$queueName:$userIgn")
        return executor.executeOrDefault(
            { performFindActiveMessageIdByUserIgn(queueName, userIgn) },
            null,
            context,
        )
    }

    // ==================== Internal Implementation ====================

    private fun <T : Any> performSend(queueName: String, message: T): Long {
        val json = objectMapper.writeValueAsString(message)

        val result = jdbcTemplate.queryForMap(
            "SELECT pgmq.send(?, ?::jsonb) as msg_id",
            queueName,
            json,
        )

        val messageId = (result["msg_id"] as Number?)?.toLong()
            ?: throw PgmqPublishException("Failed to get message ID from send result")

        log.debug("📤 [PGMQ] Sent message: queue={}, msgId={}", queueName, messageId)
        return messageId
    }

    private fun performSendRawJson(queueName: String, json: String): Long {
        val result = jdbcTemplate.queryForMap(
            "SELECT pgmq.send(?, ?::jsonb) as msg_id",
            queueName,
            json,
        )

        val messageId = (result["msg_id"] as Number?)?.toLong()
            ?: throw PgmqPublishException("Failed to get message ID from send result")

        log.debug("📤 [PGMQ] Sent raw JSON: queue={}, msgId={}", queueName, messageId)
        return messageId
    }

    private fun <T : Any> performRead(
        queueName: String,
        clazz: Class<T>,
        batchSize: Int,
        visibilityTimeoutSec: Int,
    ): List<PgmqMessage<T>> {
        val messages = jdbcTemplate.query(
            "SELECT msg_id, read_ct, enqueued_at, vt, message FROM pgmq.read(?, ?, ?)",
            ResultSetExtractor { rs -> extractMessages(rs, clazz) },
            queueName,
            batchSize,
            visibilityTimeoutSec,
        ) ?: emptyList()

        if (messages.isNotEmpty()) {
            log.debug("📥 [PGMQ] Read {} messages from queue={}", messages.size, queueName)
        }
        return messages
    }

    private fun <T : Any> extractMessages(rs: ResultSet?, clazz: Class<T>): List<PgmqMessage<T>> {
        if (rs == null) return emptyList()

        val messages = mutableListOf<PgmqMessage<T>>()
        while (rs.next()) {
            val messageId = rs.getLong("msg_id")
            val readCount = rs.getInt("read_ct")
            val enqueuedAt = rs.getTimestamp("enqueued_at").toInstant()
            val vt = rs.getTimestamp("vt").toInstant()

            // JSONB 컬럼에서 JSON 문자열 추출
            val pgObject = rs.getObject("message") as PGobject
            val json = pgObject.value
            val payload = objectMapper.readValue(json, clazz)

            messages.add(PgmqMessage.of(messageId, readCount, enqueuedAt, vt, payload))
        }
        return messages
    }

    private fun performArchive(queueName: String, messageId: Long): Boolean {
        val result = jdbcTemplate.queryForObject(
            "SELECT pgmq.archive(?, ?) as success",
            Boolean::class.java,
            queueName,
            messageId,
        ) ?: false

        if (result) {
            log.debug("✅ [PGMQ] Archived message: queue={}, msgId={}", queueName, messageId)
        }
        return result
    }

    private fun performDelete(queueName: String, messageId: Long): Boolean {
        val result = jdbcTemplate.queryForObject(
            "SELECT pgmq.delete(?, ?) as success",
            Boolean::class.java,
            queueName,
            messageId,
        ) ?: false

        if (result) {
            log.debug("🗑️ [PGMQ] Deleted message: queue={}, msgId={}", queueName, messageId)
        }
        return result
    }

    private fun performQueueLength(queueName: String): Long {
        return jdbcTemplate.queryForObject(
            "SELECT queue_length FROM pgmq.metrics(?)",
            Long::class.java,
            queueName,
        ) ?: 0L
    }

    private fun performIsArchived(queueName: String, messageId: Long): Boolean {
        validateQueueName(queueName)
        val archiveTable = "pgmq.a_$queueName"
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM $archiveTable WHERE msg_id = ?",
            Long::class.java,
            messageId,
        ) ?: 0L > 0
    }

    private fun performGetReadCount(queueName: String, messageId: Long): Int {
        validateQueueName(queueName)
        val queueTable = "pgmq.q_$queueName"
        return jdbcTemplate.queryForObject(
            "SELECT read_ct FROM $queueTable WHERE msg_id = ?",
            Int::class.java,
            messageId,
        ) ?: 0
    }

    private fun performFindActiveMessageIdByUserIgn(queueName: String, userIgn: String): Long? {
        validateQueueName(queueName)
        val queueTable = "pgmq.q_$queueName"
        return jdbcTemplate.queryForObject(
            """
            SELECT msg_id
            FROM $queueTable
            WHERE message ->> 'userIgn' = ?
            ORDER BY msg_id DESC
            LIMIT 1
            """.trimIndent(),
            Long::class.java,
            userIgn,
        )
    }

    private fun validateQueueName(queueName: String) {
        require(queueName.matches(QUEUE_NAME_PATTERN)) {
            "Invalid PGMQ queue name: $queueName"
        }
    }

    private fun performSetVisibilityTimeout(queueName: String, messageId: Long, timeoutSeconds: Long): Boolean {
        val safeSeconds = timeoutSeconds.coerceIn(1, 86400) // 최대 1일
        return jdbcTemplate.queryForObject(
            "SELECT pgmq.set_visibility_timeout(?, ?, ? * interval '1 second')",
            Boolean::class.java,
            queueName,
            messageId,
            safeSeconds,
        ) ?: false
    }

    // ==================== Fallback Methods ====================

    private fun <T : Any> sendFallback(queueName: String, message: T, e: Throwable): Long {
        log.error("⚡ [PGMQ] Circuit Breaker OPEN - send fallback: queue={}", queueName, e)
        throw PgmqPublishException("Circuit Breaker is OPEN for send operation", e)
    }

    private fun sendRawJsonFallback(queueName: String, json: String, e: Throwable): Long {
        log.error("⚡ [PGMQ] Circuit Breaker OPEN - sendRawJson fallback: queue={}", queueName, e)
        throw PgmqPublishException("Circuit Breaker is OPEN for sendRawJson operation", e)
    }

    private fun <T : Any> readFallback(
        queueName: String,
        clazz: Class<T>,
        batchSize: Int,
        visibilityTimeoutSec: Int,
        e: Throwable,
    ): List<PgmqMessage<T>> {
        log.warn("⚡ [PGMQ] Circuit Breaker OPEN - read fallback: queue={}", queueName, e)
        return emptyList() // 빈 리스트 반환으로 처리 건너뜀
    }

    private fun archiveFallback(queueName: String, messageId: Long, e: Throwable): Boolean {
        log.error("⚡ [PGMQ] Circuit Breaker OPEN - archive fallback: queue={}, msgId={}", queueName, messageId, e)
        return false
    }

    private fun deleteFallback(queueName: String, messageId: Long, e: Throwable): Boolean {
        log.error("⚡ [PGMQ] Circuit Breaker OPEN - delete fallback: queue={}, msgId={}", queueName, messageId, e)
        return false
    }

    private fun setVisibilityTimeoutFallback(
        queueName: String,
        messageId: Long,
        visibilityTimeoutSec: Long,
        e: Throwable,
    ): Boolean {
        log.error("⚡ [PGMQ] Circuit Breaker OPEN - setVT fallback: queue={}, msgId={}", queueName, messageId, e)
        return false
    }

    companion object {
        private val log = LoggerFactory.getLogger(PgmqClient::class.java)
        private val QUEUE_NAME_PATTERN = Regex("^[a-z_][a-z0-9_]{0,62}$")
    }
}

// ==================== Custom Exceptions ====================

/**
 * PGMQ 발행 예외
 */
class PgmqPublishException(message: String, cause: Throwable? = null) : SystemException(CommonErrorCode.INTERNAL_SERVER_ERROR, cause, message)

/**
 * PGMQ 읽기 예외
 */
class PgmqReadException(message: String, cause: Throwable? = null) : SystemException(CommonErrorCode.INTERNAL_SERVER_ERROR, cause, message)

/**
 * PGMQ 보관 예외
 */
class PgmqArchiveException(message: String, cause: Throwable? = null) : SystemException(CommonErrorCode.INTERNAL_SERVER_ERROR, cause, message)

/**
 * PGMQ 삭제 예외
 */
class PgmqDeleteException(message: String, cause: Throwable? = null) : SystemException(CommonErrorCode.INTERNAL_SERVER_ERROR, cause, message)
