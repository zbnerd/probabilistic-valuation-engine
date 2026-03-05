package maple.expectation.web.dto.dlq

import maple.expectation.domain.v2.DonationDlq
import java.time.LocalDateTime

/**
 * DLQ 상세 조회 응답 DTO
 *
 * Admin 전용: 전체 payload 포함 (민감 데이터 주의)
 *
 * @param id DLQ ID
 * @param originalOutboxId 원본 Outbox ID
 * @param requestId 멱등성 키
 * @param eventType 이벤트 타입
 * @param payload 전체 payload (JSON)
 * @param failureReason 실패 사유
 * @param movedAt DLQ 이동 시각
 */
data class DlqDetailResponse(
    val id: Long,
    val originalOutboxId: Long,
    val requestId: String,
    val eventType: String,
    val payload: String?,
    val failureReason: String?,
    val movedAt: LocalDateTime
) {
    companion object {
        @JvmStatic
        fun from(dlq: DonationDlq): DlqDetailResponse =
            DlqDetailResponse(
                id = dlq.id ?: 0L,
                originalOutboxId = dlq.originalOutboxId ?: 0L,
                requestId = dlq.requestId ?: "",
                eventType = dlq.eventType ?: "",
                payload = dlq.payload,
                failureReason = dlq.failureReason,
                movedAt = dlq.movedAt ?: LocalDateTime.now()
            )
    }
}
