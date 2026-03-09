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
 * RTopic 기반 좋아요 이벤트 발행자 (at-most-once)
 *
 * Issue #278: Scale-out 환경 실시간 좋아요 동기화
 *
 * Redisson RTopic을 사용하여 인스턴스 간 이벤트 Fanout
 *
 * 5-Agent Council 합의:
 * - Green (Performance): RTopic은 Redis Pub/Sub 네이티브 래퍼 → 오버헤드 최소
 * - Red (SRE): 메트릭으로 발행 성공/실패 모니터링
 * - Purple (Data): Hash Tag {likes}로 클러스터 슬롯 보장
 */
class RedisLikeEventPublisher(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.instance-id:\${HOSTNAME:unknown}}") private val instanceId: String,
) : LikeEventPublisher {

    /**
     * 좋아요 이벤트 발행
     *
     * Redis Pub/Sub 장애 시에도 좋아요 기능은 정상 동작 (Graceful Degradation)
     */
    override fun publish(event: LikeEvent) {
        val context = TaskContext.of("LikePubSub", "Publish", event.userIgn)

        val clientsReceived = executor.executeOrDefault({
            val topic = redissonClient.getTopic(RedisKey.LIKE_EVENTS_TOPIC.key)
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
        meterRegistry.counter("like.event.publish", "status", "success").increment()
    }

    private fun recordPublishFailure() {
        meterRegistry.counter("like.event.publish", "status", "failure").increment()
    }
}
