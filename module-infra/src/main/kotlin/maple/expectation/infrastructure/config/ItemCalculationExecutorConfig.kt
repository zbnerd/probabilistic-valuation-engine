package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * 장비 개별 계산 전용 Executor (ThreadPool)
 *
 * ## Phase 1→2→3 전환 이력
 *
 * ```
 * Phase 1: presetExecutor 공유 → 큐 밀림 (baseline)
 * Phase 2: itemExecutor 분리 (ThreadPool 4/8/200) → 포화 (8/8 + 200/200)
 * Phase 2.5: Virtual Thread + Semaphore(64) → CPU-bound에서 3.5× 회귀
 * Phase 3: ThreadPool 16/32/500 → 현재
 * ```
 *
 * ## 설정 근거
 *
 * - Core 16: Bulkhead 50 × ~0.3 (모든 요청이 20개 장비를 계산하지 않음)
 * - Max 32: 피크 시 여유
 * - Queue 500: fan-out 스파이크 흡수
 * - AbortPolicy: 큐 포화 시 빠른 실패
 *
 * ## Virtual Thread 기각 사유
 *
 * 장비 계산은 CPU-bound (순수 수학 연산). VT는 I/O 대기에 최적화되어
 * CPU-bound에서 스케줄링 오버헤드만 증가. 부하테스트에서 3.5× latency 회귀 확인.
 *
 * @see PresetCalculationExecutorConfig 프리셋 orchestration용 Executor
 */
@Configuration
@EnableConfigurationProperties(ExecutorProperties::class)
class ItemCalculationExecutorConfig(
    private val meterRegistry: MeterRegistry,
    private val executorProperties: ExecutorProperties,
) {
    private val log = LoggerFactory.getLogger(ItemCalculationExecutorConfig::class.java)

    @Bean("itemCalculationExecutor")
    fun itemCalculationExecutor(contextPropagatingDecorator: TaskDecorator): Executor {
        val rejectedCounter = Counter.builder("executor.rejected")
            .tag("name", "item.calculation")
            .description("Number of tasks rejected due to queue full")
            .register(meterRegistry)

        val executor = ThreadPoolTaskExecutor()
        val config = executorProperties.item
        executor.setCorePoolSize(config.corePoolSize)
        executor.setMaxPoolSize(config.maxPoolSize)
        executor.setQueueCapacity(config.queueCapacity)
        executor.setThreadNamePrefix("item-calc-")
        executor.setTaskDecorator(contextPropagatingDecorator)

        executor.setRejectedExecutionHandler { r, e ->
            rejectedCounter.increment()
            log.warn(
                "[ItemCalculationExecutor] Task rejected: active={}, poolSize={}, queueSize={}",
                e.activeCount, e.poolSize, e.queue.size,
            )
            ThreadPoolExecutor.AbortPolicy().rejectedExecution(r, e)
        }

        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)
        executor.initialize()

        ExecutorServiceMetrics(
            executor.threadPoolExecutor,
            "item.calculation",
            Collections.emptyList(),
        ).bindTo(meterRegistry)

        registerMetrics(executor)

        log.info(
            "[ItemCalculationExecutor] Initialized: core={}, max={}, queue={}",
            config.corePoolSize, config.maxPoolSize, config.queueCapacity,
        )

        return executor
    }

    private fun registerMetrics(executor: ThreadPoolTaskExecutor) {
        Gauge.builder("item.calculation.queue.size", executor) { e ->
            e.threadPoolExecutor.queue.size.toDouble()
        }.description("장비 계산 대기 큐 크기").register(meterRegistry)

        Gauge.builder("item.calculation.active.count", executor) { obj ->
            obj.activeCount.toDouble()
        }.description("장비 계산 활성 스레드 수").register(meterRegistry)

        Gauge.builder("item.calculation.pool.size", executor) { obj ->
            obj.poolSize.toDouble()
        }.description("장비 계산 현재 풀 크기").register(meterRegistry)

        Gauge.builder("item.calculation.completed.tasks", executor) { e ->
            e.threadPoolExecutor.completedTaskCount.toDouble()
        }.description("장비 계산 완료된 작업 수").register(meterRegistry)
    }
}
