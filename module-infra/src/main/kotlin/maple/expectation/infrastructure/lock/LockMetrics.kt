package maple.expectation.infrastructure.lock

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Lock Instrumentation Metrics (Issue #310 Phase 0, Issue #651 OCP Refactoring)
 *
 * <p>Map 기반 동적 등록으로 OCP 준수. 새로운 lock implementation 추가 시 이 클래스 수정 불필요.
 */
@Component
class LockMetrics(private val registry: MeterRegistry) {

    private val log = LoggerFactory.getLogger(LockMetrics::class.java)

    private data class ImplMetrics(
        val activeLocks: AtomicLong = AtomicLong(0),
        val failureCounter: io.micrometer.core.instrument.Counter,
    )

    private val implementations = ConcurrentHashMap<String, ImplMetrics>()

    // Timer (lock wait time percentiles)
    private lateinit var lockWaitTimer: Timer

    /**
     * 메트릭 초기화 (1회만 실행)
     */
    @PostConstruct
    fun init() {
        lockWaitTimer = Timer.builder("lock.wait.time")
            .description("Time spent waiting for lock acquisition")
            .tag("implementation", "all")
            .register(registry)

        // Eagerly register known implementations to ensure gauges exist at startup
        getOrCreate("postgres")
        getOrCreate("mysql")

        log.info(
            "[LockMetrics] Initialized - Timer (p50/p95/p99), Map-based dynamic Counters/Gauges registered",
        )
    }

    private fun getOrCreate(name: String): ImplMetrics =
        implementations.computeIfAbsent(name.lowercase()) {
            val activeLocks = AtomicLong(0)
            val counter = io.micrometer.core.instrument.Counter.builder("lock.acquisition.failure.total")
                .description("Total lock acquisition failures")
                .tag("implementation", it)
                .register(registry)
            Gauge.builder("lock.active.current", activeLocks) { obj: AtomicLong -> obj.get().toDouble() }
                .description("Currently active locks")
                .tag("implementation", it)
                .register(registry)
            ImplMetrics(activeLocks, counter)
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
        getOrCreate(implementation).failureCounter.increment()
        log.debug("[LockMetrics] Recorded failure for {}", implementation)
    }

    /**
     * 락 활성화 기록 (획득 성공 시 호출)
     */
    fun recordLockAcquired(implementation: String) {
        getOrCreate(implementation).activeLocks.incrementAndGet()
        log.debug("[LockMetrics] Recorded lock acquired for {}", implementation)
    }

    /**
     * 락 비활성화 기록 (해제 시 호출)
     */
    fun recordLockReleased(implementation: String) {
        getOrCreate(implementation).activeLocks.decrementAndGet()
        log.debug("[LockMetrics] Recorded lock released for {}", implementation)
    }

    /** 현재 활성 락 수 조회 (테스트용) */
    fun getActiveLocks(implementation: String): Long =
        implementations[implementation.lowercase()]?.activeLocks?.get() ?: 0L
}
