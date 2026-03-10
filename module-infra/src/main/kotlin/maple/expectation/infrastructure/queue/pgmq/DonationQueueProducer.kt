package maple.expectation.infrastructure.queue.pgmq

import java.time.Instant
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.DonationRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 기부 알림 큐 프로듀서 (ADR-002)
 *
 * <h3>역할</h3>
 * <p>기부 이벤트 알림을 PGMQ 큐에 발행
 *
 * <h3>메시지 형식</h3>
 * <pre>
 * {
 *   "donation_id": 12345,
 *   "user_id": 1,
 *   "amount": 1000,
 *   "message": "응원합니다",
 *   "requested_at": "2026-03-09T10:00:00Z"
 * }
 * </pre>
 *
 * <h3>사용 시나리오</h3>
 * <ul>
 *   <li>기부 완료 후 알림 발송
 *   <li>기부 통계 집계 트리거
 *   <li>감사 메시지 발송
 * </ul>
 *
 * @see DonationRequest 메시지 페이로드
 * @see PgmqClient PGMQ 클라이언트
 */
@Component
class DonationQueueProducer(
    private val pgmqClient: PgmqClient,
    private val executor: LogicExecutor,
) {

    /**
     * 기부 알림 요청 발행
     *
     * @param donationId 기부 ID
     * @param userId 사용자 ID
     * @param amount 기부 금액
     * @param message 기부 메시지 (선택)
     * @return 메시지 ID
     */
    fun publish(
        donationId: Long,
        userId: Long,
        amount: Long,
        message: String? = null,
    ): Long {
        val context = TaskContext.of("DonationQueue", "Publish", "donation=$donationId")

        return executor.execute(
            {
                val request = DonationRequest(
                    donationId = donationId,
                    userId = userId,
                    amount = amount,
                    message = message,
                    requestedAt = Instant.now().toString(),
                )

                val messageId = pgmqClient.send(QUEUE_NAME, request)
                log.info("📤 [DonationQueue] Published: donationId={}, userId={}, amount={}, msgId={}", donationId, userId, amount, messageId)
                messageId
            },
            context,
        )
    }

    /**
     * 일괄 기부 알림 요청 발행
     *
     * @param requests 기부 요청 목록
     * @return 발행된 메시지 ID 목록
     */
    fun publishBatch(requests: List<DonationRequest>): List<Long> {
        val context = TaskContext.of("DonationQueue", "PublishBatch", "${requests.size} items")

        return executor.execute(
            {
                requests.map { request ->
                    pgmqClient.send(QUEUE_NAME, request).also {
                        log.info("📤 [DonationQueue] Published: donationId={}, msgId={}", request.donationId, it)
                    }
                }
            },
            context,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(DonationQueueProducer::class.java)

        /** 큐 이름 */
        const val QUEUE_NAME = "donation_queue"
    }
}
