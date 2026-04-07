package maple.expectation.infrastructure.ratelimit

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 중앙 집중 Nexon Rate Limiter (ADR-355)
 *
 * <h3>역할</h3>
 * <p>분산된 Semaphore(4곳)를 단일 ReentrantLock 기반으로 통합.
 * Virtual Thread에서 Semaphore.acquire() 시 carrier thread pinning 방지.
 *
 * <h3>Scale-out 제약</h3>
 * <p>JVM-local. 다중 인스턴스 시 허용량 = maxConcurrent × N.
 * Scale-out 시 PostgreSQL Advisory Lock 전환 (별도 Issue).
 *
 * @param maxConcurrent 최대 동시 허용 수
 */
@Component
class NexonRateLimiter(
    @Value("\${nexon.rate-limit.max-concurrent:50}") maxConcurrent: Int,
    meterRegistry: MeterRegistry,
) {
    private val lock = ReentrantLock()
    private val notFull = lock.newCondition()
    private var permits = maxConcurrent
    private val maxPermits = maxConcurrent

    init {
        Gauge.builder("nexon.rate-limit.permits.available") { permits.toDouble() }
            .description("Available Nexon rate limit permits")
            .register(meterRegistry)
        log.info("[NexonRateLimiter] Initialized with maxConcurrent={}", maxConcurrent)
    }

    /**
     * Rate limit 내에서 task 실행 (동기)
     *
     * @param task 실행할 작업
     * @return task 결과
     */
    fun <T> withLimit(task: () -> T): T {
        acquirePermit()
        return try {
            task()
        } finally {
            releasePermit()
        }
    }

    /**
     * Permit 획득 (비동기 패턴용)
     *
     * <p>CompletableFuture 기반 비동기 호출에서
     * acquire → async call → whenComplete(release) 패턴에 사용.
     */
    fun acquirePermit() {
        lock.lock()
        try {
            while (permits <= 0) {
                notFull.await(100, TimeUnit.MILLISECONDS)
            }
            permits--
        } finally {
            lock.unlock()
        }
    }

    /**
     * Permit 반환 (비동기 패턴용)
     */
    fun releasePermit() {
        lock.lock()
        try {
            permits++
            notFull.signal()
        } finally {
            lock.unlock()
        }
    }

    fun availablePermits(): Int = permits

    companion object {
        private val log = LoggerFactory.getLogger(NexonRateLimiter::class.java)
    }
}
