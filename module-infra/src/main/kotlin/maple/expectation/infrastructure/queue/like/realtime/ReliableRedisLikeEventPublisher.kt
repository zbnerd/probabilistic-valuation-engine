package maple.expectation.infrastructure.queue.like.realtime

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.dto.like.LikeEvent
import maple.expectation.core.port.out.LikeEventPublisher
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.queue.RedisKey
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Value

/**
 * RReliableTopic 기반 좋아요 이벤트 발행자 (at-least-once)
 *
 * Issue #278 P0: Scale-out 환경 실시간 좋아요 동기화
 *
 * Redisson RReliableTopic을 사용하여 인스턴스 간 이벤트 Fanout
 * RTopic(at-most-once)과 달리 인스턴스 재시작 시에도 메시지 유실 방지
 *
 * 5-Agent Council 합의:
 * - Purple (Data): L1 eviction은 idempotent → 중복 수신 무해
 * - Purple (Data): Hash Tag {likes} 호환성 문제 없음 (RReliableTopic 내부 키도 동일 슬롯)
 * - Red (SRE): 메트릭으로 발행 성공/실패 + transport 태그로 구분
 * - Green (Performance): countSubscribers() Gauge로 구독자 수 모니터링
 *
 * RTopic과의 차이:
 * - RTopic: Redis Pub/Sub → at-most-once, 수신자 없으면 유실
 * - RReliableTopic: Redis Stream 기반 → at-least-once, watchdog 지원
 */
class ReliableRedisLikeEventPublisher(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.instance-id:\${HOSTNAME:unknown}}") private val instanceId: String
) : LikeEventPublisher {

    /**
     * 좋아요 이벤트 발행
     *
     * Redis Pub/Sub 장애 시에도 좋아요 기능은 정상 동작 (Graceful Degradation)
     */
    override fun publish(event: LikeEvent) {
        val context = TaskContext.of("LikePubSub", "Publish", event.userIgn)

        val clientsReceived = executor.executeOrDefault({
            val topic = redissonClient.getReliableTopic(RedisKey.LIKE_EVENTS_RELIABLE_TOPIC.key)
            topic.publish(event)
        }, 0L, context)

        if (clientsReceived > 0) {
            recordPublishSuccess()
        } else {
            recordPublishFailure()
        }
    }

    override fun publishLike(userIgn: String, newDelta: Long) {
        val event = LikeEvent.like(userIgn, newDelta, instanceId)
        publish(event)
    }

    override fun publishUnlike(userIgn: String, newDelta: Long) {
        val event = LikeEvent.unlike(userIgn, newDelta, instanceId)
        publish(event)
    }

    // ==================== Metrics ====================

    private fun recordPublishSuccess() {
        meterRegistry.counter("like.event.publish", "status", "success", "transport", "reliable").increment()
    }

    private fun recordPublishFailure() {
        meterRegistry.counter("like.event.publish", "status", "failure", "transport", "reliable").increment()
    }
}
