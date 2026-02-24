package maple.expectation.infrastructure.cache.invalidation.impl

import io.micrometer.core.instrument.MeterRegistry
import lombok.extern.slf4j.Slf4j.Slf4j
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationEvent
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationPublisher
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.queue.RedisKey
import org.redisson.api.RTopic
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component

/**
 * Redis RTopic 기반 캐시 무효화 이벤트 발행자
 *
 * <h3>Issue #278: Scale-out 환경 L1 Cache Coherence</h3>
 *
 * <p>Redisson RTopic을 사용하여 인스턴스 간 캐시 무효화 이벤트 Fanout
 *
 * <h3>P1-3: RTopic 필드 캐싱</h3>
 *
 * <p>매번 redissonClient.getTopic() 호출 대신 생성자에서 1회 캐싱
 *
 * <h3>CLAUDE.md Section 12: LogicExecutor 패턴</h3>
 *
 * <p>모든 Redis 작업은 executeOrDefault로 Graceful Degradation
 */
@Slf4j
@Component
class RedisCacheInvalidationPublisher(
    redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry
) : CacheInvalidationPublisher {

    // P1-3: 생성자에서 캐싱
    private val topic: RTopic = redissonClient.getTopic(RedisKey.CACHE_INVALIDATION_TOPIC.key)

    /**
     * 캐시 무효화 이벤트 발행
     *
     * <p>Redis Pub/Sub 장애 시에도 캐시 기능은 정상 동작 (TTL fallback)
     */
    override fun publish(event: CacheInvalidationEvent) {
        val context = TaskContext.of("CacheInvalidation", "Publish", event.cacheName)

        val clientsReceived = executor.executeOrDefault(
            { topic.publish(event) },
            0L,
            context
        )

        recordPublishResult(clientsReceived, event)
    }

    /** 발행 결과 메트릭 및 로그 기록 */
    private fun recordPublishResult(clientsReceived: Long, event: CacheInvalidationEvent) {
        if (clientsReceived > 0) {
            meterRegistry.counter("cache.invalidation.publish", "status", "success").increment()
            log.debug(
                "[CacheInvalidation] Published: cache={}, type={}, key={}, clients={}",
                event.cacheName,
                event.type,
                event.key,
                clientsReceived
            )
        } else {
            meterRegistry.counter("cache.invalidation.publish", "status", "failure").increment()
            log.warn(
                "[CacheInvalidation] Publish failed or no subscribers: cache={}, type={}",
                event.cacheName,
                event.type
            )
        }
    }
}
