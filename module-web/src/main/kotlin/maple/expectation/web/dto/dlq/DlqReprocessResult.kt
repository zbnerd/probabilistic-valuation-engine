package maple.expectation.web.dto.dlq

/**
 * DLQ 재처리 결과 응답 DTO
 *
 * @param dlqId 처리된 DLQ ID
 * @param newOutboxId 새로 생성된 Outbox ID
 * @param requestId 멱등성 키
 * @param message 처리 결과 메시지
 */
data class DlqReprocessResult(
    val dlqId: Long,
    val newOutboxId: Long?,
    val requestId: String,
    val message: String
) {
    companion object {
        @JvmStatic
        fun success(dlqId: Long, newOutboxId: Long?, requestId: String): DlqReprocessResult =
            DlqReprocessResult(
                dlqId = dlqId,
                newOutboxId = newOutboxId,
                requestId = requestId,
                message = "Successfully requeued to Outbox for reprocessing"
            )
    }
}
