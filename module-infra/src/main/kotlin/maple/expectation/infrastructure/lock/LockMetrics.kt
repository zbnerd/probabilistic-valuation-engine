package maple.expectation.infrastructure.lock

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Lock Instrumentation Metrics (Issue #310 Phase 0)
 */
@Component
class LockMetrics(private val registry: MeterRegistry) {

    private val log = LoggerFactory.getLogger(LockMetrics::class.java)

    // Timer (lock wait time percentiles)
    private lateinit var lockWaitTimer: Timer

    // Counters (thread-safe)
    private lateinit var redisFailureCounter: io.micrometer.core.instrument.Counter
    private lateinit var mysqlFailureCounter: io.micrometer.core.instrument.Counter

    // Gauge backing fields
    private val redisActiveLocks = AtomicLong(0)
    private val mysqlActiveLocks = AtomicLong(0)

    /**
     * 메트릭 초기화 (1회만 실행)
     */
    @PostConstruct
    fun init() {
        // Timer 초기화 (lock wait time percentiles)
        lockWaitTimer = Timer.builder("lock.wait.time")
            .description("Time spent waiting for lock acquisition")
            .tag("implementation", "all")
            .register(registry)

        // Counters 초기화 (tag: implementation)
        redisFailureCounter = io.micrometer.core.instrument.Counter.builder("lock.acquisition.failure.total")
            .description("Total lock acquisition failures")
            .tag("implementation", "redis")
            .register(registry)

        mysqlFailureCounter = io.micrometer.core.instrument.Counter.builder("lock.acquisition.failure.total")
            .description("Total lock acquisition failures")
            .tag("implementation", "mysql")
            .register(registry)

        // Gauges 초기화 (1회만)
        Gauge.builder("lock.active.current", redisActiveLocks) { obj: AtomicLong -> obj.get().toDouble() }
            .description("Currently active locks")
            .tag("implementation", "redis")
            .register(registry)

        Gauge.builder("lock.active.current", mysqlActiveLocks) { obj: AtomicLong -> obj.get().toDouble() }
            .description("Currently active locks")
            .tag("implementation", "mysql")
            .register(registry)

        log.info(
            "[LockMetrics] Initialized - Timer (p50/p95/p99), Counters (redis/mysql failures), Gauges (active locks) registered",
        )
    }

    /**
     * 락 대기 시간 기록
     */
    fun recordWaitTime(waitTimeMs: Long, implementation: String) {
        lockWaitTimer.record(waitTimeMs, TimeUnit.MILLISECONDS)
        log.debug("[LockMetrics] Recorded wait time: {}ms for {}", waitTimeMs, implementation)
    }

    /**
     * 락 획득 실패 기록
     */
    fun recordFailure(implementation: String) {
        when (implementation.lowercase()) {
            "redis" -> redisFailureCounter.increment()
            "mysql" -> mysqlFailureCounter.increment()
            else -> log.warn("[LockMetrics] Unknown implementation: {}", implementation)
        }
        log.debug("[LockMetrics] Recorded failure for {}", implementation)
    }

    /**
     * 락 활성화 기록 (획득 성공 시 호출)
     */
    fun recordLockAcquired(implementation: String) {
        when (implementation.lowercase()) {
            "redis" -> redisActiveLocks.incrementAndGet()
            "mysql" -> mysqlActiveLocks.incrementAndGet()
            else -> log.warn("[LockMetrics] Unknown implementation: {}", implementation)
        }
        log.debug("[LockMetrics] Recorded lock acquired for {}", implementation)
    }

    /**
     * 락 비활성화 기록 (해제 시 호출)
     */
    fun recordLockReleased(implementation: String) {
        when (implementation.lowercase()) {
            "redis" -> redisActiveLocks.decrementAndGet()
            "mysql" -> mysqlActiveLocks.decrementAndGet()
            else -> log.warn("[LockMetrics] Unknown implementation: {}", implementation)
        }
        log.debug("[LockMetrics] Recorded lock released for {}", implementation)
    }

    /** 현재 활성 락 수 조회 (테스트용) */
    fun getActiveLocks(implementation: String): Long = when (implementation.lowercase()) {
        "redis" -> redisActiveLocks.get()
        "mysql" -> mysqlActiveLocks.get()
        else -> 0L
    }
}
