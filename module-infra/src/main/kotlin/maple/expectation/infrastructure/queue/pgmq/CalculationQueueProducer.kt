package maple.expectation.infrastructure.queue.pgmq

import java.time.Instant
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.CalculationRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 계산 큐 프로듀서 (ADR-002)
 *
 * <h3>역할</h3>
 * <p>장비 기대값 계산 요청을 PGMQ 큐에 발행
 *
 * <h3>메시지 형식</h3>
 * <pre>
 * {
 *   "ocid": "abc123",
 *   "user_ign": "닉네임",
 *   "preset_no": 1,
 *   "force_recalculation": false,
 *   "requested_at": "2026-03-09T10:00:00Z"
 * }
 * </pre>
 *
 * <h3>트랜잭션 통합</h3>
 * <p>Service 계층에서 @Transactional 내에서 호출하면
 * DB 작업과 메시지 발행이 동일 트랜잭션에서 원자적으로 처리됨.
 *
 * @see CalculationRequest 메시지 페이로드
 * @see PgmqClient PGMQ 클라이언트
 */
@Component
class CalculationQueueProducer(
    private val pgmqClient: PgmqClient,
    private val executor: LogicExecutor,
) {

    /**
     * 계산 요청 발행
     *
     * @param ocid 캐릭터 OCID
     * @param userIgn 사용자 IGN
     * @param presetNo 프리셋 번호 (기본값: 1)
     * @param forceRecalculation 강제 재계산 여부
     * @return 메시지 ID
     */
    fun publish(
        ocid: String,
        userIgn: String,
        presetNo: Int = 1,
        forceRecalculation: Boolean = false,
    ): Long {
        val context = TaskContext.of("CalculationQueue", "Publish", userIgn)

        return executor.execute(
            {
                val request = CalculationRequest(
                    ocid = ocid,
                    userIgn = userIgn,
                    presetNo = presetNo,
                    forceRecalculation = forceRecalculation,
                    requestedAt = Instant.now().toString(),
                )

                val messageId = pgmqClient.send(QUEUE_NAME, request)
                log.debug("📤 [CalculationQueue] Published: ign={}, msgId={}", userIgn, messageId)
                messageId
            },
            context,
        )
    }

    /**
     * 일괄 계산 요청 발행
     *
     * @param requests 계산 요청 목록
     * @return 발행된 메시지 ID 목록
     */
    fun publishBatch(requests: List<CalculationRequest>): List<Long> {
        val context = TaskContext.of("CalculationQueue", "PublishBatch", "${requests.size} items")

        return executor.execute(
            {
                requests.map { request ->
                    pgmqClient.send(QUEUE_NAME, request).also {
                        log.debug("📤 [CalculationQueue] Published: ign={}, msgId={}", request.userIgn, it)
                    }
                }
            },
            context,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(CalculationQueueProducer::class.java)

        /** 큐 이름 */
        const val QUEUE_NAME = "calculation_queue"
    }
}
