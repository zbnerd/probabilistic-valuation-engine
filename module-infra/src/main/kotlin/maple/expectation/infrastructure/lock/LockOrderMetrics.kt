package maple.expectation.infrastructure.lock

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PostConstruct
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Lock Ordering 메트릭 관리 (Issue #228: N09-Circular Lock)
 */
@Component
class LockOrderMetrics(private val registry: MeterRegistry) {

    private val log = LoggerFactory.getLogger(LockOrderMetrics::class.java)

    // Counters (Thread-safe)
    private lateinit var violationCounter: Counter
    private lateinit var acquisitionCounter: Counter

    // Gauge backing fields
    private val currentHeldLocks = AtomicLong(0)

    /**
     * 메트릭 초기화 (1회만 실행)
     */
    @PostConstruct
    fun init() {
        // Counters 초기화
        violationCounter = registry.counter("lock.order.violation.total")
        acquisitionCounter = registry.counter("lock.acquisition.total")

        // Gauge 초기화 (1회만)
        registry.gauge("lock.held.current", currentHeldLocks) { obj: AtomicLong -> obj.get().toDouble() }

        log.info(
            "[LockOrderMetrics] Initialized - violation/acquisition counters and held gauge registered",
        )
    }

    /**
     * 락 순서 위반 기록
     */
    fun recordViolation(currentLock: String, previousLock: String) {
        violationCounter.increment()

        // 태그 기반 상세 메트릭 (선택적)
        registry.counter(
            "lock.order.violation.detail",
            Tags.of(
                "current",
                sanitizeKey(currentLock),
                "previous",
                sanitizeKey(previousLock),
            ),
        ).increment()

        log.warn(
            "[LockOrder] Violation recorded: '{}' requested after '{}' - potential deadlock risk",
            currentLock,
            previousLock,
        )
    }

    /**
     * 락 획득 기록
     */
    fun recordAcquisition(lockKey: String) {
        acquisitionCounter.increment()
        currentHeldLocks.incrementAndGet()
    }

    /**
     * 락 해제 기록
     */
    fun recordRelease(lockKey: String) {
        currentHeldLocks.decrementAndGet()
    }

    /** 현재 보유 중인 락 수 조회 (테스트용) */
    val currentHeldLocksCount: Long
        get() = currentHeldLocks.get()

    /**
     * 메트릭 태그용 키 정규화
     */
    private fun sanitizeKey(key: String?): String {
        if (key == null) {
            return "unknown"
        }
        // 최대 50자로 제한, 특수문자 제거
        val sanitized = key.replace("[^a-zA-Z0-9\\-:]".toRegex(), "_")
        return if (sanitized.length > 50) sanitized.substring(0, 50) else sanitized
    }
}
