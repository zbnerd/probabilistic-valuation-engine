package maple.expectation.infrastructure.queue.pgmq

import java.time.Instant
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.NexonCollectionRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Nexon 데이터 수집 큐 프로듀서 (ADR-006)
 *
 * <h3>역할</h3>
 * <p>Nexon API에서 수집한 원본 데이터를 PGMQ 큐에 발행하여 CalculationWorker가 처리
 *
 * <h3>메시지 형식</h3>
 * <pre>
 * {
 *   "ocid": "abc123",
 *   "user_ign": "닉네임",
 *   "requested_at": "2026-03-10T10:00:00Z"
 * }
 * </pre>
 *
 * @see NexonCollectionRequest 메시지 페이로드
 * @see PgmqClient PGMQ 클라이언트
 */
@Component
class NexonDataQueueProducer(
    private val pgmqClient: PgmqClient,
    private val executor: LogicExecutor,
) {

    /**
     * Nexon 데이터 수집 요청 발행
     *
     * @param ocid 캐릭터 OCID
     * @param userIgn 사용자 IGN
     * @return 메시지 ID
     */
    fun publish(ocid: String, userIgn: String): Long {
        val context = TaskContext.of("NexonDataQueue", "Publish", userIgn)

        return executor.execute(
            {
                val request = NexonCollectionRequest(
                    ocid = ocid,
                    userIgn = userIgn,
                    requestedAt = Instant.now().toString(),
                )

                val messageId = pgmqClient.send(QUEUE_NAME, request)
                log.debug("📤 [NexonDataQueue] Published: ign={}, msgId={}", userIgn, messageId)
                messageId
            },
            context,
        )
    }

    /**
     * 일괄 수집 요청 발행
     *
     * @param requests 수집 요청 목록
     * @return 발행된 메시지 ID 목록
     */
    fun publishBatch(requests: List<NexonCollectionRequest>): List<Long> {
        val context = TaskContext.of("NexonDataQueue", "PublishBatch", "${requests.size} items")

        return executor.execute(
            {
                requests.map { request ->
                    pgmqClient.send(QUEUE_NAME, request).also {
                        log.debug("📤 [NexonDataQueue] Published: ign={}, msgId={}", request.userIgn, it)
                    }
                }
            },
            context,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(NexonDataQueueProducer::class.java)

        /** 큐 이름 */
        const val QUEUE_NAME = "calculation_queue"
    }
}
