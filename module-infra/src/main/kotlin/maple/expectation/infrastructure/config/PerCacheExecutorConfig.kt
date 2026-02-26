package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

/**
 * PER (Probabilistic Early Recomputation) 전용 Thread Pool (#219)
 *
 * ## SRE 요구사항 (Red Agent)
 *
 * - 기존 비즈니스 Thread Pool과 분리하여 자원 격리
 * - DiscardPolicy: 큐 포화 시 갱신 작업 버림 (기존 Stale 데이터 유지)
 * - 보수적인 Pool 크기: Core 2, Max 4
 *
 * ## 장애 격리
 *
 * PER 갱신 작업이 폭증해도 메인 비즈니스 로직에 영향을 주지 않음.
 *
 * @see maple.expectation.infrastructure.cache.per.ProbabilisticCacheAspect
 */
@Configuration
class PerCacheExecutorConfig(
    private val meterRegistry: MeterRegistry
) {

    /**
     * PER 전용 Executor
     *
     * ## 설정 근거
     *
     * - Core 2: 평상시 백그라운드 갱신 처리
     * - Max 4: 트래픽 증가 시 탄력적 확장
     * - Queue 100: Burst 대응, 초과 시 버림
     * - DiscardPolicy: Stale 데이터가 이미 있으므로 안전하게 버림
     *
     * ## 메트릭 노출 (#238 5-Agent Council P2-B)
     *
     * - per.cache.executor.queue.size: 큐 대기 작업 수
     * - per.cache.executor.active.count: 활성 스레드 수
     * - per.cache.executor.pool.size: 현재 풀 크기
     * - per.cache.executor.completed.tasks: 완료된 작업 수
     */
    @Bean("perCacheExecutor")
    fun perCacheExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.setCorePoolSize(2)
        executor.setMaxPoolSize(4)
        executor.setQueueCapacity(100)
        executor.setThreadNamePrefix("per-cache-")

        // DiscardPolicy: 큐 포화 시 새 작업 버림
        // 기존 Stale 데이터가 있으므로 버려도 서비스 영향 없음
        executor.setRejectedExecutionHandler(ThreadPoolExecutor.DiscardPolicy())

        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)
        executor.initialize()

        // 메트릭 노출 (SRE Red Agent 요구사항)
        registerMetrics(executor)

        return executor
    }

    /**
     * Thread Pool 메트릭 등록
     *
     * Prometheus/Grafana에서 PER 전용 Thread Pool 상태를 모니터링 가능
     */
    private fun registerMetrics(executor: ThreadPoolTaskExecutor) {
        Gauge.builder(
            "per.cache.executor.queue.size",
            executor
        ) { e -> e.threadPoolExecutor.queue.size.toDouble() }
            .description("PER 캐시 갱신 대기 큐 크기")
            .register(meterRegistry)

        Gauge.builder(
            "per.cache.executor.active.count",
            executor
        ) { obj: ThreadPoolTaskExecutor -> obj.activeCount.toDouble() }
            .description("PER 캐시 갱신 활성 스레드 수")
            .register(meterRegistry)

        Gauge.builder(
            "per.cache.executor.pool.size",
            executor
        ) { obj: ThreadPoolTaskExecutor -> obj.poolSize.toDouble() }
            .description("PER 캐시 갱신 현재 풀 크기")
            .register(meterRegistry)

        Gauge.builder(
            "per.cache.executor.completed.tasks",
            executor
        ) { e -> e.threadPoolExecutor.completedTaskCount.toDouble() }
            .description("PER 캐시 갱신 완료된 작업 수")
            .register(meterRegistry)
    }
}
