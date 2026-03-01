package maple.expectation.core.port.out

/**
 * 좋아요 이벤트 발행 인터페이스 (Issue #278)
 *
 * <h3>역할</h3>
 *
 * <p>좋아요 상태 변경 이벤트를 발행하여 다른 인스턴스의 캐시를 무효화합니다.
 *
 * <h3>구현체</h3>
 *
 * <ul>
 *   <li>RedisLikeEventPublisher: RTopic 기반 (at-most-once)
 *   <li>ReliableRedisLikeEventPublisher: RReliableTopic 기반 (at-least-once)
 * </ul>
 */
interface LikeEventPublisher {

    /**
     * 좋아요 이벤트 발행
     *
     * @param accountId 계정 ID
     * @param targetOcid 대상 OCID
     * @param liked 좋아요 여부 (true: 좋아요, false: 좋아요 취소)
     */
    fun publishLikeEvent(accountId: String, targetOcid: String, liked: Boolean)

    /**
     * 전송 타입 반환
     *
     * @return 전송 타입 (RTOPIC, RELIABLE_TOPIC)
     */
    fun getTransportType(): TransportType

    enum class TransportType {
        RTOPIC,
        RELIABLE_TOPIC
    }
}

/**
 * 좋아요 이벤트 구독 인터페이스 (Issue #278)
 *
 * <h3>역할</h3>
 *
 * <p>다른 인스턴스에서 발행한 좋아요 이벤트를 수신하여 로컬 캐시를 무효화합니다.
 *
 * <h3>구현체</h3>
 *
 * <ul>
 *   <li>RedisLikeEventSubscriber: RTopic 기반 (at-most-once)
 *   <li>ReliableRedisLikeEventSubscriber: RReliableTopic 기반 (at-least-once)
 * </ul>
 */
interface LikeEventSubscriber {

    /**
     * 구독 시작
     */
    fun subscribe()

    /**
     * 구독 중지
     */
    fun unsubscribe()

    /**
     * 전송 타입 반환
     *
     * @return 전송 타입 (RTOPIC, RELIABLE_TOPIC)
     */
    fun getTransportType(): LikeEventPublisher.TransportType
}
