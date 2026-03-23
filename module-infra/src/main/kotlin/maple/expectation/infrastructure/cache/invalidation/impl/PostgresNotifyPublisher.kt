package maple.expectation.infrastructure.cache.invalidation.impl

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationEvent
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationPublisher
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * PostgreSQL LISTEN/NOTIFY 기반 캐시 무효화 이벤트 발행자
 *
 * <h3>Issue #278: Scale-out 환경 L1 Cache Coherence</h3>
 *
 * <p>PostgreSQL NOTIFY를 사용하여 인스턴스 간 캐시 무효화 이벤트 Fanout
 *
 * <h3>동작 방식</h3>
 *
 * <ul>
 *   <li>Channel: cache_invalidation_{cacheName}</li>
 *   <li>Payload: JSON 직렬화된 CacheInvalidationEvent</li>
 *   <li>NOTIFY는 비동기로 전송되며, 수신자가 없어도 에러 없음</li>
 * </ul>
 *
 * <h3>CLAUDE.md Section 12: LogicExecutor 패턴</h3>
 *
 * <p>모든 DB 작업은 executeOrDefault로 Graceful Degradation
 *
 * @see PostgresNotifySubscriber 수신자 구현
 */
@Component
class PostgresNotifyPublisher(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) : CacheInvalidationPublisher {
    companion object {
        private val log = LoggerFactory.getLogger(PostgresNotifyPublisher::class.java)
        private const val CHANNEL = "cache_invalidation"
    }

    /**
     * 캐시 무효화 이벤트 발행
     *
     * <p>PostgreSQL NOTIFY는 비동기로 전송되며, 수신자가 없어도 에러가 발생하지 않음.
     * DB 연결 실패 시에도 캐시 기능은 정상 동작 (TTL fallback)
     */
    override fun publish(event: CacheInvalidationEvent) {
        val context = TaskContext.of("CacheInvalidation", "PostgresNotify", event.cacheName)

        val published = executor.executeOrDefault(
            { performNotify(event) },
            false,
            context,
        )

        recordPublishResult(published, event)
    }

    /** NOTIFY 실행 */
    private fun performNotify(event: CacheInvalidationEvent): Boolean {
        val payload = objectMapper.writeValueAsString(event)

        // PostgreSQL NOTIFY with payload (single channel for all caches)
        jdbcTemplate.execute("NOTIFY \"$CHANNEL\", '$payload'")

        return true
    }

    /** 발행 결과 메트릭 및 로그 기록 */
    private fun recordPublishResult(published: Boolean, event: CacheInvalidationEvent) {
        if (published) {
            meterRegistry.counter("cache.invalidation.publish", "impl", "postgres", "status", "success").increment()
            log.debug(
                "[PostgresNotify] Published: cache={}, type={}, key={}",
                event.cacheName,
                event.type,
                event.key,
            )
        } else {
            meterRegistry.counter("cache.invalidation.publish", "impl", "postgres", "status", "failure").increment()
            log.warn(
                "[PostgresNotify] Publish failed: cache={}, type={}",
                event.cacheName,
                event.type,
            )
        }
    }
}
