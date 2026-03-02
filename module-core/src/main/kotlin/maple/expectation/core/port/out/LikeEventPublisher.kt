package maple.expectation.core.port.out

import maple.expectation.core.dto.like.LikeEvent

/**
 * 좋아요 이벤트 발행 인터페이스 (Issue #278)
 *
 * 역할:
 * 좋아요 상태 변경 이벤트를 발행하여 다른 인스턴스의 캐시를 무효화합니다.
 *
 * 구현체:
 * - RedisLikeEventPublisher: RTopic 기반 (at-most-once)
 * - ReliableRedisLikeEventPublisher: RReliableTopic 기반 (at-least-once)
 *
 * 5-Agent Council 합의:
 * - Blue (Architect): Strategy 패턴으로 구현체 교체 가능
 * - Green (Performance): RTopic은 Redis Pub/Sub 네이티브 래퍼 → 오버헤드 최소
 * - Red (SRE): 메트릭으로 발행 성공/실패 모니터링
 */
interface LikeEventPublisher {

    /**
     * 좋아요 이벤트 발행
     *
     * @param event 발행할 이벤트
     */
    fun publish(event: LikeEvent)

    /**
     * 좋아요 추가 이벤트 발행 (편의 메서드)
     *
     * @param userIgn 대상 캐릭터 닉네임
     * @param newDelta 버퍼의 새 delta 값
     */
    fun publishLike(userIgn: String, newDelta: Long)

    /**
     * 좋아요 취소 이벤트 발행 (편의 메서드)
     *
     * @param userIgn 대상 캐릭터 닉네임
     * @param newDelta 버퍼의 새 delta 값
     */
    fun publishUnlike(userIgn: String, newDelta: Long)
}

/**
 * 좋아요 이벤트 구독 인터페이스 (Issue #278)
 *
 * 역할:
 * 다른 인스턴스에서 발행한 좋아요 이벤트를 수신하여 로컬 캐시를 무효화합니다.
 *
 * 구현체:
 * - RedisLikeEventSubscriber: RTopic 기반 (at-most-once)
 * - ReliableRedisLikeEventSubscriber: RReliableTopic 기반 (at-least-once)
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
     * 이벤트 수신 처리
     *
     * @param event 수신된 이벤트
     */
    fun onEvent(event: LikeEvent)
}
