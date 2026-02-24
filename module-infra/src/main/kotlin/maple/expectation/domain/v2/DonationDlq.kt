package maple.expectation.domain.v2

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Dead Letter Queue 엔티티 (Issue #80)
 *
 * <h3>Triple Safety Net 1차 안전망</h3>
 *
 * <p>Outbox 처리 실패 시 데이터 영구 손실을 방지하기 위한 DLQ 테이블.
 *
 * <ul>
 *   <li>1차: DB DLQ INSERT (이 엔티티)
 *   <li>2차: File Backup (DLQ 실패 시)
 *   <li>3차: Discord Critical Alert
 * </ul>
 *
 * @see maple.expectation.service.v2.donation.outbox.DlqHandler
 */
@Entity
@Table(
    name = "donation_dlq",
    indexes = [
        Index(name = "idx_dlq_moved_at", columnList = "moved_at"),
        Index(name = "idx_dlq_request_id", columnList = "request_id")
    ]
)
class DonationDlq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false)
    var originalOutboxId: Long? = null
        private set

    @Column(nullable = false, length = 50)
    var requestId: String? = null
        private set

    @Column(nullable = false, length = 50)
    var eventType: String? = null
        private set

    @Column(columnDefinition = "TEXT", nullable = false)
    var payload: String? = null
        private set

    @Column(length = 500)
    var failureReason: String? = null
        private set

    @Column(updatable = false)
    var movedAt: LocalDateTime? = null
        private set

    private constructor()

    /** Outbox 엔티티에서 DLQ 엔티티 생성 */
    companion object {
        fun from(outbox: DonationOutbox, reason: String): DonationDlq {
            val dlq = DonationDlq()
            dlq.originalOutboxId = outbox.id
            dlq.requestId = outbox.requestId
            dlq.eventType = outbox.eventType
            dlq.payload = outbox.payload
            dlq.failureReason = truncate(reason, 500)
            dlq.movedAt = LocalDateTime.now()
            return dlq
        }

        private fun truncate(str: String?, maxLen: Int): String? {
            return if (str != null && str.length > maxLen) str.substring(0, maxLen) else str
        }
    }

    /** PII 마스킹 (CLAUDE.md 19 준수) */
    override fun toString(): String {
        return "DonationDlq[id=$id, requestId=$requestId, failureReason=$failureReason, payload=MASKED]"
    }
}
