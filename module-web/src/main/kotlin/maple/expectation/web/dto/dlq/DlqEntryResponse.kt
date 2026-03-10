package maple.expectation.web.dto.dlq

import java.time.LocalDateTime
import maple.expectation.domain.v2.DonationDlq

/**
 * DLQ 조회 응답 DTO
 *
 * CLAUDE.md 19 준수: payload 마스킹 처리
 *
 * @param id DLQ ID
 * @param originalOutboxId 원본 Outbox ID
 * @param requestId 멱등성 키
 * @param eventType 이벤트 타입
 * @param payloadPreview payload 미리보기 (100자)
 * @param failureReason 실패 사유
 * @param movedAt DLQ 이동 시각
 */
data class DlqEntryResponse(
    val id: Long,
    val originalOutboxId: Long,
    val requestId: String,
    val eventType: String,
    val payloadPreview: String?,
    val failureReason: String?,
    val movedAt: LocalDateTime,
) {
    companion object {
        private const val PREVIEW_LENGTH = 100

        @JvmStatic
        fun from(dlq: DonationDlq): DlqEntryResponse = DlqEntryResponse(
            id = dlq.id ?: 0L,
            originalOutboxId = dlq.originalOutboxId ?: 0L,
            requestId = dlq.requestId ?: "",
            eventType = dlq.eventType ?: "",
            payloadPreview = truncatePayload(dlq.payload),
            failureReason = dlq.failureReason,
            movedAt = dlq.movedAt ?: LocalDateTime.now(),
        )

        private fun truncatePayload(payload: String?): String? {
            if (payload == null) return null
            if (payload.length <= PREVIEW_LENGTH) return payload
            return payload.substring(0, PREVIEW_LENGTH) + "..."
        }
    }
}
