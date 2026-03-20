package maple.expectation.infrastructure.cache.invalidation

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationEvent
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationPublisher
import maple.expectation.infrastructure.cache.invalidation.InvalidationType
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * @TransactionalEventListener + PostgreSQL NOTIFY로 Atomic Cache Invalidation
 *
 * <h3>Issue #562: Load Testing + Optimization</h3>
 *
 * <h3>핵심 기능</h3>
 *
 * <ul>
 *   <li><b>Atomic Cache Invalidation</b>: 캐시 무효화를 DB 트랜잭션 내에서 수행
 *   <li><b>Zero Infrastructure</b>: PostgreSQL NOTIFY 사용 (Redis 불필요)
 *   <li><b>Higher Consistency</b>: 트랜잭션 커밋과 같은 타이밍으로 무효화 이벤트 발행
 * </ul>
 *
 * <h3>ADR 논거</h3>
 *
 * <blockquote>
 * 캐시 무효화를 별도 인프라 없이 DB 트랜잭션과 동일한 원자성으로 처리
* </blockquote>
 *
 * <h3>Flow</h3>
 *
 * <pre>
 * 1. Data 변경 (Service Layer)
 *    ↓
 * 2. @Transactional(BEGIN) - 트랜잭션 시작
*    ↓
 * 3. 데이터 수정 (Repository Layer)
*    ↓
 * 4. ApplicationEventPublisher.publish(CacheInvalidationEvent)
 *    ↓
 * 5. @TransactionalEventListener(BEFORE_COMMIT) intercepts
*    ↓
 * 6. PostgresNotifyPublisher.publish(event) - same transaction!
*    ↓
 * 7. NOTIFY sent to PostgreSQL
*    ↓
 * 8. Other instances LISTEN and invalidate L1 cache
 * </pre>
 *
 * <h3>Why BEFORE_COMMIT?</h3>
 *
 * <ul>
 *   <li>이벤트 발행이 트랜잭션의 일부여가 롬   <li>이벤트 발행 실패 → 트랜잭션 롤백 (무효화 이벤트 없음)
 *   <li>AFTER_COMMIT에서 실행되면 새 트랜잭션에서 이벤트가 유실될 수 있음
 * </ul>
 *
 * @see CacheInvalidationEvent
 * @see PostgresNotifyPublisher
 */
@Component
class TransactionalCacheInvalidationListener(
    private val publisher: CacheInvalidationPublisher,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        private val log = LoggerFactory.getLogger(TransactionalCacheInvalidationListener::class.java)
    }

    /**
     * 데이터 변경 후 캐시 무효화 이벤트 발행
     *
     * <p>이 메서드는 트랜잭션 내에서 호출됩니다. 이렇게 하면 캐시와 DB 데이터의 정합성을 유지할 수 있.
     *
     * @param event 캐시 무효화 이벤트
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun handleCacheInvalidation(event: CacheInvalidationEvent) {
        val context = TaskContext.of("CacheInvalidation", "TransactionalNotify", event.cacheName)

        val published = executor.executeOrDefault(
            { doPublish(event) },
            false,
            context,
        )

        if (published) {
            log.debug(
                "[TransactionalCacheInvalidation] Atomic invalidation published: cache={}, type={}, key={}",
                event.cacheName,
                event.type,
                event.key,
            )
            meterRegistry.counter(
                "cache.invalidation.atomic",
                "impl",
                "postgres",
                "status",
                "success",
            ).increment()
        } else {
            log.warn(
                "[TransactionalCacheInvalidation] Failed to publish invalidation: cache={}, type={}",
                event.cacheName,
                event.type,
            )
            meterRegistry.counter(
                "cache.invalidation.atomic",
                "impl",
                "postgres",
                "status",
                "failure",
            ).increment()
        }
    }

    private fun doPublish(event: CacheInvalidationEvent): Boolean {
        publisher.publish(event)
        return true
    }
}
