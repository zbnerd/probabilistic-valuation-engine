package maple.expectation.domain.v2

import jakarta.persistence.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.LocalDateTime
import java.util.HexFormat
import kotlin.math.min
import kotlin.math.pow
import maple.expectation.core.domain.nexon.NexonApiEventType
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.SystemException

/**
 * Nexon API용 Transactional Outbox 엔티티 (N19 리팩토링)
 *
 * <p>N19: 외부 API 6시간 장애 → Outbox 적재 → Replay/Reconciliation
 *
 * <h3>사용 목적</h3>
 *
 * <ul>
 *   <li>Nexon API 호출 실패 시 Outbox에 적재
 *   <li>OutboxProcessor가 주기적으로 재시도
 *   <li>장애 복구 후 자동 재처리
 * </ul>
 */
@Entity
@Table(
    indexes = [
        Index(name = "idx_pending_poll", columnList = "status, next_retry_at, id"),
        Index(name = "idx_locked", columnList = "locked_by, locked_at"),
    ],
)
class NexonApiOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    var version: Long? = null

    @Column(nullable = false, unique = true, length = 100)
    var requestId: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    var eventType: NexonApiEventType? = null

    @Column(columnDefinition = "TEXT", nullable = false)
    var payload: String? = null

    /** Content Hash (무결성 검증) */
    @Column(nullable = false, length = 64)
    var contentHash: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OutboxStatus = OutboxStatus.PENDING

    @Column(length = 100)
    var lockedBy: String? = null

    var lockedAt: LocalDateTime? = null

    @Column(nullable = false)
    var retryCount: Int = 0

    @Column(nullable = false)
    var maxRetries: Int = 3

    @Column(length = 500)
    var lastError: String? = null

    var nextRetryAt: LocalDateTime? = null

    @Column(updatable = false)
    var createdAt: LocalDateTime? = null

    var updatedAt: LocalDateTime? = null

    /** Outbox 상태 */
    enum class OutboxStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        DEAD_LETTER,
    }

    private constructor()

    companion object {
        /** 팩토리 메서드 (Outbox 적재) */
        fun create(
            requestId: String,
            eventType: NexonApiEventType,
            payload: String,
        ): NexonApiOutbox {
            val outbox = NexonApiOutbox()
            outbox.requestId = requestId
            outbox.eventType = eventType
            outbox.payload = payload
            outbox.contentHash = computeContentHash(requestId, eventType, payload)
            outbox.status = OutboxStatus.PENDING
            outbox.nextRetryAt = LocalDateTime.now()
            outbox.createdAt = LocalDateTime.now()
            outbox.updatedAt = LocalDateTime.now()
            return outbox
        }

        private fun computeContentHash(
            requestId: String,
            eventType: NexonApiEventType,
            payload: String,
        ): String = try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(
                ("$requestId|$eventType|$payload").toByteArray(StandardCharsets.UTF_8),
            )
            HexFormat.of().formatHex(hash)
        } catch (e: NoSuchAlgorithmException) {
            // SHA-256은 JVM 필수 알고리즘이므로 여기 도달 시 JVM 결함
            throw SystemException(CommonErrorCode.INTERNAL_SERVER_ERROR, "SHA-256 not available", e)
        }
    }

    /** 처리 시작 마킹 */
    fun markProcessing(instanceId: String) {
        this.status = OutboxStatus.PROCESSING
        this.lockedBy = instanceId
        this.lockedAt = LocalDateTime.now()
        this.updatedAt = LocalDateTime.now()
    }

    /** 처리 완료 마킹 */
    fun markCompleted() {
        this.status = OutboxStatus.COMPLETED
        clearLock()
    }

    /** 처리 실패 마킹 (Exponential Backoff) */
    fun markFailed(error: String) {
        this.retryCount++
        this.lastError = truncate(error, 500)
        this.status = if (shouldMoveToDlq()) OutboxStatus.DEAD_LETTER else OutboxStatus.FAILED
        val backoffSeconds = min(2.0.pow(retryCount.toDouble()).toLong() * 30, 3600) // Max 1시간
        this.nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds)
        clearLock()
    }

    /** DLQ 이동 여부 판단 */
    fun shouldMoveToDlq(): Boolean = retryCount >= maxRetries

    /** 강제 DLQ 이동 (무결성 실패 등) */
    fun forceDeadLetter() {
        this.status = OutboxStatus.DEAD_LETTER
        clearLock()
    }

    /** Stalled 상태에서 재시도 가능 상태로 복원 */
    fun resetToRetry() {
        this.status = OutboxStatus.PENDING
        clearLock()
    }

    /** 무결성 검증 */
    fun verifyIntegrity(): Boolean {
        val expectedHash = computeContentHash(requestId!!, eventType!!, payload!!)
        return expectedHash == contentHash
    }

    /** 락 해제 */
    private fun clearLock() {
        this.lockedBy = null
        this.lockedAt = null
        this.updatedAt = LocalDateTime.now()
    }

    private fun truncate(str: String?, maxLen: Int): String? = if (str != null && str.length > maxLen) str.substring(0, maxLen) else str

    /** PII 마스킹 */
    override fun toString(): String = "NexonApiOutbox[id=$id, requestId=$requestId, status=$status, eventType=$eventType, payload=MASKED]"
}
