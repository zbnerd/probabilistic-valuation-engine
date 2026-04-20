package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 장비 개별 계산 전용 Virtual Thread Executor
 *
 * ## Phase 1 → Phase 2 전환
 *
 * ```
 * Phase 1 (ThreadPool): core=4, max=8, queue=200
 *   → 8/8 active + 200/200 queue = 완전 포화 → 새로운 병목
 *
 * Phase 2 (Virtual Thread): unbounded + Semaphore(64)
 *   → 큐 대기 없음, Semaphore로 동시성 제한만
 *   → Bulkhead 50 × ~20 items = 1000 VT 동시 생성 가능하지만
 *     Semaphore로 CPU 보호
 * ```
 *
 * ## Virtual Thread 선택 근거
 *
 * - 장비 계산은 CPU-bound이지만 CompletableFuture 체인 내에서 실행되어
 *   스레드 점유 시간이 김. VT는 메모리 오버헤드가 극히 낮아
 *   수천 개 동시 생성해도 안정적
 * - Platform thread 8개로는 fan-out(60 items/request)을 감당 불가
 * - Semaphore로 실제 CPU 활용도 제어 (64 = 8코어 × 8배수)
 *
 * @see PresetCalculationExecutorConfig 프리셋 orchestration용 Executor
 */
@Configuration
class ItemCalculationExecutorConfig(
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(ItemCalculationExecutorConfig::class.java)

    companion object {
        private const val MAX_CONCURRENT = 64
    }

    @Bean("itemCalculationExecutor")
    fun itemCalculationExecutor(): Executor {
        val semaphore = Semaphore(MAX_CONCURRENT)
        val vtExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
        val activeCount = AtomicInteger(0)
        val completedCount = AtomicLong(0)
        val rejectedCounter = Counter.builder("executor.rejected")
            .tag("name", "item.calculation")
            .description("Number of tasks rejected due to semaphore timeout")
            .register(meterRegistry)

        // Micrometer standard metrics
        ExecutorServiceMetrics(
            vtExecutor,
            "item.calculation",
            Collections.emptyList(),
        ).bindTo(meterRegistry)

        // Custom metrics for VT observability
        Gauge.builder("item.calculation.active.count", activeCount) { it.get().toDouble() }
            .description("장비 계산 활성 Virtual Thread 수")
            .register(meterRegistry)
        Gauge.builder("item.calculation.completed.tasks", completedCount) { it.get().toDouble() }
            .description("장비 계산 완료된 작업 수")
            .register(meterRegistry)
        Gauge.builder("item.calculation.semaphore.available", semaphore) { it.availablePermits().toDouble() }
            .description("장비 계산 Semaphore 잔여 permit")
            .register(meterRegistry)

        log.info("[ItemCalculationExecutor] Virtual Thread executor initialized: semaphore={}", MAX_CONCURRENT)

        return Executor { runnable ->
            vtExecutor.execute {
                val acquired = semaphore.tryAcquire(30, TimeUnit.SECONDS)
                if (!acquired) {
                    log.warn("[ItemCalculationExecutor] Semaphore timeout - 동시성 한도 초과 (limit={})", MAX_CONCURRENT)
                    rejectedCounter.increment()
                    return@execute
                }
                activeCount.incrementAndGet()
                try {
                    runnable.run()
                } finally {
                    activeCount.decrementAndGet()
                    completedCount.incrementAndGet()
                    semaphore.release()
                }
            }
        }
    }
}
