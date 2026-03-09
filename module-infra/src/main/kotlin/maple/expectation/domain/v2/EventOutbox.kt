package maple.expectation.domain.v2

import jakarta.persistence.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.LocalDateTime
import java.util.HexFormat
import kotlin.math.min
import kotlin.math.pow
import maple.expectation.error.exception.InternalSystemException

/**
 * Generic Event Outbox Entity for Multi-Stream Support (Issue #490)
 *
 * <h3>Multi-Stream Event Outbox Pattern</h3>
 *
 * <ul>
 *   <li>{@code targetStream}: Redis stream name for multi-stream routing (e.g., "character-sync", "guild-sync")
 *   <li>{@code @Version}: Optimistic Locking for concurrent modification detection
 *   <li>{@code contentHash}: Individual record integrity verification (distributed environment safety)
 *   <li>SKIP LOCKED compatible: status + nextRetryAt + id index
 * </ul>
 *
 * <h4>Key Differences from DonationOutbox</h4>
 *
 * <ul>
 *   <li>Generic: Supports multiple event types and target streams
 *   <li>No requestId: Uses composite key of targetStream + eventType + contentHash for uniqueness
 *   <li>Multi-stream: Routes events to different Redis streams based on targetStream field
 * </ul>
 *
 * @see maple.expectation.infrastructure.persistence.repository.EventOutboxRepository
 */
@Entity
@Table(
    indexes = [
        Index(name = "idx_event_pending_poll", columnList = "status, next_retry_at, id"),
        Index(name = "idx_event_locked", columnList = "locked_by, locked_at"),
        Index(name = "idx_event_target_stream", columnList = "target_stream, status"),
    ],
)
class EventOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    var version: Long? = null

    /**
     * Target Redis Stream Name
     *
     * <p>Examples: "character-sync", "guild-sync", "equipment-sync"
     * Used for routing events to specific Redis streams
     */
    @Column(nullable = false, length = 100)
    var targetStream: String? = null

    @Column(nullable = false, length = 50)
    var eventType: String? = null

    @Column(columnDefinition = "TEXT", nullable = false)
    var payload: String? = null

    /**
     * Content Hash (distributed environment safety)
     *
     * <p>Verifies individual record integrity (no hash chain to avoid concurrency issues)
     */
    @Column(nullable = false, length = 64)
    var contentHash: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: EventOutboxStatus = EventOutboxStatus.PENDING

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

    /** Event Outbox Status */
    enum class EventOutboxStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        DEAD_LETTER,
    }

    private constructor()

    companion object {
        /**
         * Factory Method (Content Hash auto-generated)
         *
         * @param targetStream Redis stream name (e.g., "character-sync")
         * @param eventType Event type for routing (e.g., "CharacterCreated")
         * @param payload JSON payload
         */
        @JvmStatic
        fun create(targetStream: String, eventType: String, payload: String): EventOutbox {
            val outbox = EventOutbox()
            outbox.targetStream = targetStream
            outbox.eventType = eventType
            outbox.payload = payload
            outbox.contentHash = computeContentHash(targetStream, eventType, payload)
            outbox.status = EventOutboxStatus.PENDING
            outbox.nextRetryAt = LocalDateTime.now()
            outbox.createdAt = LocalDateTime.now()
            outbox.updatedAt = LocalDateTime.now()
            return outbox
        }

        /**
         * Content Hash Calculation (CLAUDE.md Section 11 compliance: InternalSystemException)
         */
        private fun computeContentHash(stream: String, type: String, payload: String): String {
            val digest = getSha256Digest()
            val hash = digest.digest(("$stream|$type|$payload").toByteArray(StandardCharsets.UTF_8))
            return bytesToHex(hash)
        }

        /**
         * SHA-256 MessageDigest Acquisition
         *
         * <p><b>CLAUDE.md Section 11 Compliance:</b> Convert checked exception to InternalSystemException
         *
         * <p>SHA-256 is a Java standard required algorithm (RFC 8018), so NoSuchAlgorithmException cannot occur.
         * If it occurs, it's a JVM defect.
         *
         * <p><b>Note:</b> LogicExecutor injection is not possible in JPA entities, so direct exception
         * transformation is allowed per Section 11 rules
         */
        @Suppress("JAVA_S1166") // NoSuchAlgorithmException cannot occur (Java standard)
        private fun getSha256Digest(): MessageDigest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (e: NoSuchAlgorithmException) {
            // SHA-256 is a required JVM algorithm, reaching here means JVM defect
            throw InternalSystemException("SHA-256 algorithm not available (JVM defect)", e)
        }

        /**
         * P1-9 Fix: HexFormat (Java 17+) for GC optimization
         *
         * <p>Reduces allocations compared to legacy String.format("%02x") loop
         */
        private fun bytesToHex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)
    }

    /** Integrity verification */
    fun verifyIntegrity(): Boolean {
        val expected = computeContentHash(targetStream!!, eventType!!, payload!!)
        return contentHash == expected
    }

    /** Mark processing start */
    fun markProcessing(instanceId: String) {
        this.status = EventOutboxStatus.PROCESSING
        this.lockedBy = instanceId
        this.lockedAt = LocalDateTime.now()
        this.updatedAt = LocalDateTime.now()
    }

    /** Mark processing complete */
    fun markCompleted() {
        this.status = EventOutboxStatus.COMPLETED
        clearLock()
    }

    /** Exponential Backoff max wait time (P1-5 Fix: overflow prevention) */
    private val maxBackoff = java.time.Duration.ofHours(1)

    /**
     * Mark processing failed (Exponential Backoff + P1-5 Cap)
     *
     * <p>P1-5 Fix: Even with large retryCount, MAX_BACKOFF (1 hour) is not exceeded
     */
    fun markFailed(error: String) {
        this.retryCount++
        this.lastError = truncate(error, 500)
        this.status = if (shouldMoveToDlq()) EventOutboxStatus.DEAD_LETTER else EventOutboxStatus.FAILED
        val backoffSeconds = min(2.0.pow(retryCount.toDouble()).toLong() * 30, maxBackoff.seconds)
        this.nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds)
        clearLock()
    }

    /** DLQ move decision */
    fun shouldMoveToDlq(): Boolean = retryCount >= maxRetries

    /**
     * Force DEAD_LETTER status (Purple requirement)
     *
     * <p>Used for cases where retry is meaningless (e.g., integrity verification failure)
     */
    fun forceDeadLetter() {
        this.status = EventOutboxStatus.DEAD_LETTER
        this.updatedAt = LocalDateTime.now()
    }

    /**
     * Restore from Stalled to PENDING (#229)
     *
     * <p>Used to recover Zombie state caused by JVM crash. Should only be called after integrity verification passes.
     */
    fun resetToRetry() {
        this.status = EventOutboxStatus.PENDING
        this.nextRetryAt = LocalDateTime.now()
        clearLock()
    }

    private fun clearLock() {
        this.lockedBy = null
        this.lockedAt = null
        this.updatedAt = LocalDateTime.now()
    }

    private fun truncate(str: String?, maxLen: Int): String? = if (str != null && str.length > maxLen) str.substring(0, maxLen) else str

    /** PII Masking (CLAUDE.md 19 compliance) */
    override fun toString(): String = "EventOutbox[id=$id, targetStream=$targetStream, eventType=$eventType, status=$status, payload=MASKED]"
}
