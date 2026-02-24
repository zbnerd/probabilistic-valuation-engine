package maple.expectation.domain.v2

import jakarta.persistence.*
import maple.expectation.error.exception.InternalSystemException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.LocalDateTime
import java.util.HexFormat
import kotlin.math.min
import kotlin.math.pow

/**
 * Transactional Outbox 엔티티 (Issue #80)
 *
 * <h3>Financial-Grade 특성</h3>
 *
 * <ul>
 *   <li>{@code @Version}: Optimistic Locking으로 동시 수정 감지
 *   <li>{@code contentHash}: 개별 레코드 무결성 검증 (분산 환경 안전)
 *   <li>SKIP LOCKED 호환: status + nextRetryAt 인덱스
 * </ul>
 *
 * @see maple.expectation.repository.v2.DonationOutboxRepository
 */
@Entity
@Table(
    indexes = [
        Index(name = "idx_pending_poll", columnList = "status, next_retry_at, id"),
        Index(name = "idx_locked", columnList = "locked_by, locked_at")
    ]
)
class DonationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Version
    var version: Long? = null
        private set

    @Column(nullable = false, unique = true, length = 50)
    var requestId: String? = null
        private set

    @Column(nullable = false, length = 50)
    var eventType: String? = null
        private set

    @Column(columnDefinition = "TEXT", nullable = false)
    var payload: String? = null
        private set

    /**
     * Content Hash (분산 환경 안전)
     *
     * <p>Hash Chain 대신 개별 레코드 무결성만 검증 (동시성 문제 제거)
     */
    @Column(nullable = false, length = 64)
    var contentHash: String? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OutboxStatus = OutboxStatus.PENDING
        private set

    @Column(length = 100)
    var lockedBy: String? = null
        private set

    var lockedAt: LocalDateTime? = null
        private set

    @Column(nullable = false)
    var retryCount: Int = 0
        private set

    @Column(nullable = false)
    var maxRetries: Int = 3
        private set

    @Column(length = 500)
    var lastError: String? = null
        private set

    var nextRetryAt: LocalDateTime? = null
        private set

    @Column(updatable = false)
    var createdAt: LocalDateTime? = null
        private set

    var updatedAt: LocalDateTime? = null
        private set

    /** Outbox 상태 */
    enum class OutboxStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        DEAD_LETTER
    }

    private constructor()

    companion object {
        /** 팩토리 메서드 (Content Hash 자동 생성) */
        @JvmStatic
        fun create(requestId: String, eventType: String, payload: String): DonationOutbox {
            val outbox = DonationOutbox()
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

        /**
         * Content Hash 계산 (CLAUDE.md Section 11 준수: InternalSystemException 사용)
         */
        private fun computeContentHash(reqId: String, type: String, payload: String): String {
            val digest = getSha256Digest()
            val hash = digest.digest(("$reqId|$type|$payload").toByteArray(StandardCharsets.UTF_8))
            return bytesToHex(hash)
        }

        /**
         * SHA-256 MessageDigest 획득
         *
         * <p><b>CLAUDE.md Section 11 준수:</b> 체크 예외를 InternalSystemException으로 변환
         *
         * <p>SHA-256은 Java 표준 필수 알고리즘 (RFC 8018)이므로 NoSuchAlgorithmException이 발생할 수 없음. 발생 시 JVM 결함.
         *
         * <p><b>Note:</b> JPA 엔티티에서는 LogicExecutor 주입이 불가하므로 Section 11 규칙에 따라 직접 예외 변환 허용
         */
        @Suppress("JAVA_S1166") // NoSuchAlgorithmException은 발생 불가 (Java 표준)
        private fun getSha256Digest(): MessageDigest {
            return try {
                MessageDigest.getInstance("SHA-256")
            } catch (e: NoSuchAlgorithmException) {
                // SHA-256은 JVM 필수 알고리즘이므로 여기 도달 시 JVM 결함
                throw InternalSystemException("SHA-256 algorithm not available (JVM defect)", e)
            }
        }

        /**
         * P1-9 Fix: HexFormat (Java 17+) 사용으로 GC 최적화
         *
         * <p>기존 String.format("%02x") 루프 대비 할당 감소
         */
        private fun bytesToHex(bytes: ByteArray): String {
            return HexFormat.of().formatHex(bytes)
        }
    }

    /** 무결성 검증 */
    fun verifyIntegrity(): Boolean {
        val expected = computeContentHash(requestId!!, eventType!!, payload!!)
        return contentHash == expected
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

    /** Exponential Backoff 최대 대기 시간 (P1-5 Fix: 오버플로 방지) */
    private val maxBackoff = java.time.Duration.ofHours(1)

    /**
     * 처리 실패 마킹 (Exponential Backoff + P1-5 Cap)
     *
     * <p>P1-5 Fix: retryCount가 커져도 MAX_BACKOFF(1시간)을 초과하지 않음
     */
    fun markFailed(error: String) {
        this.retryCount++
        this.lastError = truncate(error, 500)
        this.status = if (shouldMoveToDlq()) OutboxStatus.DEAD_LETTER else OutboxStatus.FAILED
        val backoffSeconds = min(2.0.pow(retryCount.toDouble()).toLong() * 30, maxBackoff.seconds)
        this.nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds)
        clearLock()
    }

    /** DLQ 이동 여부 판단 */
    fun shouldMoveToDlq(): Boolean {
        return retryCount >= maxRetries
    }

    /**
     * 즉시 DEAD_LETTER 상태로 강제 변경 (Purple 요구사항)
     *
     * <p>무결성 검증 실패 등 재시도가 무의미한 경우 사용
     */
    fun forceDeadLetter() {
        this.status = OutboxStatus.DEAD_LETTER
        this.updatedAt = LocalDateTime.now()
    }

    /**
     * Stalled 상태에서 PENDING으로 복원 (#229)
     *
     * <p>JVM 크래시로 인한 Zombie 상태를 복구하기 위해 사용. 무결성 검증 통과 후에만 호출되어야 함.
     */
    fun resetToRetry() {
        this.status = OutboxStatus.PENDING
        this.nextRetryAt = LocalDateTime.now()
        clearLock()
    }

    private fun clearLock() {
        this.lockedBy = null
        this.lockedAt = null
        this.updatedAt = LocalDateTime.now()
    }

    private fun truncate(str: String?, maxLen: Int): String? {
        return if (str != null && str.length > maxLen) str.substring(0, maxLen) else str
    }

    /** PII 마스킹 (CLAUDE.md 19 준수) */
    override fun toString(): String {
        return "DonationOutbox[id=$id, requestId=$requestId, status=$status, payload=MASKED]"
    }
}
