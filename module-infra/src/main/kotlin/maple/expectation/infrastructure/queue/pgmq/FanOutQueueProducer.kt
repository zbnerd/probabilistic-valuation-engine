package maple.expectation.infrastructure.queue.pgmq

import java.time.Instant
import maple.expectation.core.port.out.FanOutQueuePort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.FanOutRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * FanOut 큐 프로듀서
 *
 * <h3>역할</h3>
 * <p>429 Rate Limit 발생 시 nexon_fanout_queue에 재시도 메시지를 발행
 *
 * <h3>트랜잭션</h3>
 * <p>PgmqClient.send()가 활성 트랜잭션을 요구하므로 @Transactional 필수
 *
 * @see FanOutRequest 메시지 페이로드
 * @see PgmqClient PGMQ 클라이언트
 * @see FanOutQueuePort Port 인터페이스
 */
@Component
class FanOutQueueProducer(
    private val pgmqClient: PgmqClient,
    private val executor: LogicExecutor,
) : FanOutQueuePort {

    @Transactional
    override fun enqueue(ocid: String, userIgn: String, retryCount: Int): Long {
        val context = TaskContext.of("FanOutQueue", "Enqueue", ocid)

        return executor.execute(
            {
                val request = FanOutRequest(
                    ocid = ocid,
                    userIgn = userIgn,
                    retryCount = retryCount,
                    requestedAt = Instant.now().toString(),
                )

                val messageId = pgmqClient.send(QUEUE_NAME, request)
                log.debug("[FanOutQueue] Enqueued: ocid={}, retry={}, msgId={}", ocid, retryCount, messageId)
                messageId
            },
            context,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(FanOutQueueProducer::class.java)

        /** 큐 이름 */
        const val QUEUE_NAME = "nexon_fanout_queue"
    }
}
