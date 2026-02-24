package maple.expectation.domain.v2

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Dead Letter Queue 엔티티 for Nexon API Outbox (Issue #333)
 *
 * <h3>Triple Safety Net 1차 안전망</h3>
 *
 * <p>NexonApiOutbox 처리 실패 시 데이터 영구 손실을 방지하기 위한 DLQ 테이블.
 *
 * <ul>
 *   <li>1차: DB DLQ INSERT (이 엔티티)
 *   <li>2차: File Backup (DLQ 실패 시)
 *   <li>3차: Discord Critical Alert
 * </ul>
 *
 * <h3>Design Pattern</h3>
 *
 * <p>DonationDlq 패턴을 따르며 Nexon API 특화 필드 포함
 *
 * @see maple.expectation.service.v2.outbox.NexonApiDlqHandler
 * @see NexonApiOutbox
 */
@Entity
@Table(
    name = "nexon_api_dlq",
    indexes = [
        Index(name = "idx_dlq_moved_at", columnList = "moved_at"),
        Index(name = "idx_dlq_request_id", columnList = "request_id")
    ]
)
class NexonApiDlq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false)
    var originalOutboxId: Long? = null
        private set

    @Column(nullable = false, length = 100)
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

    companion object {
        /**
         * Outbox 엔티티에서 DLQ 엔티티 생성
         *
         * @param outbox 실패한 NexonApiOutbox 엔티티
         * @param reason 실패 사유
         * @return NexonApiDlq 엔티티
         */
        fun from(outbox: NexonApiOutbox, reason: String): NexonApiDlq {
            val dlq = NexonApiDlq()
            dlq.originalOutboxId = outbox.id
            dlq.requestId = outbox.requestId
            dlq.eventType = outbox.eventType?.name
            dlq.payload = outbox.payload
            dlq.failureReason = truncate(reason, 500)
            dlq.movedAt = LocalDateTime.now()
            return dlq
        }

        /**
         * 문자열 자르기 (DB 컬럼 길이 제한 준수)
         *
         * @param str 원본 문자열
         * @param maxLen 최대 길이
         * @return 자른 문자열
         */
        private fun truncate(str: String?, maxLen: Int): String? {
            return if (str != null && str.length > maxLen) str.substring(0, maxLen) else str
        }
    }

    /**
     * PII 마스킹 (CLAUDE.md Section 19 준수)
     *
     * <p>로그 출력 시 payload 내용을 마스킹하여 민감 정보 노출 방지
     */
    override fun toString(): String {
        return "NexonApiDlq[id=$id, requestId=$requestId, eventType=$eventType, failureReason=$failureReason, payload=MASKED]"
    }
}
