package maple.expectation.infrastructure.queue.like.compensation

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.core.dto.like.FetchResult
import maple.expectation.core.port.out.like.CompensationCommand
import maple.expectation.core.port.out.like.LikeAtomicFetchStrategy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.queue.like.event.LikeSyncFailedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher

/**
 * Redis 보상 트랜잭션 명령 구현 (Command Pattern)
 *
 * 금융수준 안전 설계:
 * - Thread-Safe: AtomicReference/AtomicBoolean으로 동시성 보장
 * - 멱등성: compensate()는 한 번만 실행됨
 * - Graceful Degradation: 복구 실패 시 로그만 남기고 진행
 *
 * @since 2.0.0
 */
class RedisCompensationCommand(
    private val sourceKey: String,
    private val strategy: LikeAtomicFetchStrategy,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val eventPublisher: ApplicationEventPublisher,
) : CompensationCommand {

    private val log = LoggerFactory.getLogger(javaClass)
    private val savedResult = AtomicReference<FetchResult>(null)
    private val committed = AtomicBoolean(false)

    override fun save(result: FetchResult) {
        if (result.isEmpty()) {
            return
        }
        savedResult.set(result)
        log.debug("Compensation state saved: tempKey={}, entries={}", result.tempKey, result.size())
    }

    override fun compensate() {
        val result = savedResult.get() ?: return
        if (result.isEmpty()) return

        // 이미 커밋됨 → 보상 불필요
        if (committed.get()) return

        // 메트릭 기록 (보상 트랜잭션 발동)
        recordCompensationTriggered()

        executor.executeOrCatch(
            {
                strategy.restore(result.tempKey, sourceKey)
                log.warn(
                    "Compensation triggered: tempKey={} -> sourceKey={}, entries={}",
                    result.tempKey,
                    sourceKey,
                    result.size(),
                )
                null
            },
            { e ->
                // P0 FIX: 복구 실패 시 DLQ 이벤트 발행 (데이터 영구 손실 방지)
                log.error(
                    "Compensation FAILED - Publishing DLQ event: tempKey={}, sourceKey={}, reason={}",
                    result.tempKey,
                    sourceKey,
                    e.message,
                )
                publishDlqEvent(result, e)
                null
            },
            TaskContext.of("Compensation", "restore", result.tempKey),
        )
    }

    // ========== DLQ (Dead Letter Queue) ==========

    private fun publishDlqEvent(result: FetchResult, cause: Throwable) {
        executor.executeOrCatch(
            {
                val event = LikeSyncFailedEvent.fromFetchResult(result, sourceKey, cause)
                eventPublisher.publishEvent(event)
                log.info("DLQ event published: tempKey={}, entries={}", result.tempKey, result.size())
                null
            },
            { e ->
                // 이벤트 발행마저 실패 → 로그에 데이터 직접 기록 (최후의 보루)
                log.error(
                    "🚨 [CRITICAL] DLQ event publish failed! Data logged for manual recovery: {}",
                    result.data,
                    e,
                )
                null
            },
            TaskContext.of("Compensation", "publishDlqEvent", result.tempKey),
        )
    }

    // ========== Metrics (Micrometer) ==========

    private fun recordCompensationTriggered() {
        meterRegistry.counter("cache.compensation.triggered").increment()
    }

    override fun commit() {
        val result = savedResult.get()
        if (result == null || result.isEmpty()) {
            committed.set(true)
            return
        }

        // CAS로 중복 커밋 방지
        if (!committed.compareAndSet(false, true)) {
            return
        }

        executor.executeOrCatch(
            {
                strategy.deleteTempKey(result.tempKey)
                log.debug("Compensation committed: tempKey={} deleted", result.tempKey)
                null
            },
            { e ->
                // 삭제 실패 시 TTL에 의해 자동 만료됨
                log.warn("TempKey delete failed (will expire by TTL): tempKey={}", result.tempKey)
                null
            },
            TaskContext.of("Compensation", "commit", result.tempKey),
        )
    }

    override fun isPending(): Boolean = savedResult.get() != null && !committed.get()
}
