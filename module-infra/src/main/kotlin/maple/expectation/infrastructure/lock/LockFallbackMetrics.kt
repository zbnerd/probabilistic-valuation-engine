package maple.expectation.infrastructure.lock

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Redis Lock Fallback 메트릭 (Issue #310 Phase 2)
 */
@Component
class LockFallbackMetrics(private val registry: MeterRegistry) {

    private val log = LoggerFactory.getLogger(LockFallbackMetrics::class.java)

    // Counters
    private lateinit var redisFailureCounter: Counter
    private lateinit var mysqlFallbackCounter: Counter
    private lateinit var mysqlUnavailableCounter: Counter

    // Timer
    private lateinit var fallbackLatencyTimer: Timer

    @PostConstruct
    fun init() {
        redisFailureCounter = registry.counter("lock.redis.failure.total", Tags.of("layer", "tiered_lock"))
        mysqlFallbackCounter = registry.counter("lock.mysql.fallback.total", Tags.of("layer", "tiered_lock"))
        mysqlUnavailableCounter = registry.counter("lock.redis.unavailable.total", Tags.of("layer", "tiered_lock"))
        fallbackLatencyTimer = registry.timer("lock.mysql.fallback.latency", Tags.of("layer", "tiered_lock"))

        log.info("[LockFallbackMetrics] Initialized - fallback tracking enabled")
    }

    /**
     * Redis 락 실패 기록
     */
    fun recordRedisFailure(lockKey: String, reason: String, circuitBreakerState: String) {
        redisFailureCounter.increment()

        // 상세 메트릭 (원인별)
        registry.counter(
            "lock.redis.failure.detail",
            Tags.of("reason", sanitizeReason(reason), "cb_state", circuitBreakerState),
        ).increment()

        log.warn(
            "[LockFallback] Redis failure recorded - key={}, reason={}, state={}",
            lockKey,
            reason,
            circuitBreakerState,
        )
    }

    /**
     * MySQL Fallback 활성화 기록
     */
    fun recordMysqlFallback(lockKey: String, circuitBreakerState: String) {
        mysqlFallbackCounter.increment()

        // 상태별 메트릭
        registry.counter("lock.mysql.fallback.detail", Tags.of("cb_state", circuitBreakerState))
            .increment()

        log.warn(
            "[LockFallback] MySQL fallback activated - key={}, state={}",
            lockKey,
            circuitBreakerState,
        )
    }

    /**
     * Fallback 지연 시간 기록
     */
    fun recordFallbackLatency(operation: String, durationMillis: Long) {
        fallbackLatencyTimer.record(durationMillis, TimeUnit.MILLISECONDS)

        log.debug("[LockFallback] Fallback latency - op={}, duration={}ms", operation, durationMillis)
    }

    /**
     * MySQL Fallback 불가능 상황 기록
     */
    fun recordMysqlUnavailable(lockKey: String, reason: String) {
        mysqlUnavailableCounter.increment()

        log.error("[LockFallback] MySQL unavailable - key={}, reason={}", lockKey, reason)
    }

    /** 메트릭 태그용 원인 정규화 */
    private fun sanitizeReason(reason: String?): String {
        if (reason == null) {
            return "unknown"
        }
        // 예외 클래스 이름만 추출
        val lastDot = reason.lastIndexOf('.')
        return if (lastDot > 0) reason.substring(lastDot + 1) else reason
    }
}
