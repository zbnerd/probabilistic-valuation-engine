package maple.expectation.core.port.out

/**
 * FanOut 큐 발행 Port (DIP - Hexagonal Architecture)
 *
 * <p>Rate-limit 재시도 메시지를 큐로 발행하는 outbound port.
 * 실제 메시지 브로커는 adapter가 결정.
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
