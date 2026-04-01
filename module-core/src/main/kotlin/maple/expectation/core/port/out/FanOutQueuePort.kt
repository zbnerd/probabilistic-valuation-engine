package maple.expectation.core.port.out

/**
 * FanOut 큐 발행 Port (DIP - Hexagonal Architecture)
 *
 * <h3>역할</h3>
 * <p>429 Rate Limit 발생 시 Batch Lane에서 PGMQ로 재시도 메시지를 발행
 *
 * <h3>구현체</h3>
 * <p>module-infra의 FanOutQueueProducer
 *
 * @see maple.expectation.infrastructure.queue.pgmq.FanOutQueueProducer
 */
interface FanOutQueuePort {

    /**
     * FanOut 재시도 메시지를 큐에 발행
     *
     * @param ocid 캐릭터 OCID
     * @param userIgn 사용자 IGN
     * @param retryCount 현재 재시도 횟수 (기본값: 0)
     * @return 메시지 ID
     */
    fun enqueue(ocid: String, userIgn: String, retryCount: Int = 0): Long
}
