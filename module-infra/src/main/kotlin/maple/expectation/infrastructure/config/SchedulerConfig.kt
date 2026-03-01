package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import maple.expectation.infrastructure.shutdown.ShutdownProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.Collections
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Spring Scheduler Thread Pool Configuration
 *
 * ## Issue #344: Connection Pool Exhaustion from fixedRate Overlap
 *
 * ### 문제 상황
 *
 * Spring Boot의 기본 `TaskScheduler`는 단일 스레드(`poolSize=1`)로 생성됩니다. 10개 이상의
 * `@Scheduled` 메서드가 있는 상황에서:
 *
 * - **fixedRate**: 이전 작업 종료와 무관하게 주기적 실행 → 중복 실행 가능
 * - **poolSize=1**: 모든 스케줄 작업이 단일 큐에서 대기 → 지연 누적
 *
 * 이로 인해 다음과 같은 문제가 발생합니다:
 *
 * - **Connection Pool 고갈**: 겹친 스케줄 작업이 DB 커넥션을 동시 점유
 * - **Deadlock**: Scheduler가 다른 스케줄 작업의 완료를 기다리는 순환 의존성 발생 가능
 *
 * ### 해결 방안
 *
 * 명시적인 `ThreadPoolTaskScheduler` 빈을 생성하여:
 *
 * - **적절한 poolSize**: 3-4 스레드로 10개 스케줄러 병렬 처리
 * - **Metric 가시성**: Micrometer로 스케줄러 상태 모니터링
 * - **Graceful Shutdown**: 앱 종료 시 진행 중인 작업 완료 보장
 *
 * ## 왜 fixedDelay에서도 명시적 poolSize가 필요한가?
 *
 * 대부분의 스케줄러가 `fixedDelay`라 하더라도 명시적 설정이 필요합니다:
 *
 * - **동시성 요구**: 여러 독립적인 작업을 동시에 실행하여 전체 처리량 향상
 * - **장애 격리**: 단일 작업의 장애가 다른 작업의 실행을 차단하지 않음
 * - **가시성**: Micrometer 메트릭으로 스케줄러 상태 모니터링
 *
 * ## Graceful Shutdown的重要性
 *
 * 스케줄러는 장기 실행 작업(DB 백업, 배치 작업 등)을 수행할 수 있으므로:
 *
 * - **waitForTasksToCompleteOnShutdown=true**: 앱 종료 시 진행 중인 작업 완료 대기
 * - **awaitTerminationSeconds=60**: 최대 60초 대기 후 강제 종료
 *
 * 이를 통해:
 *
 * - DB 트랜잭션 중단으로 인한 데이터 불일치 방지
 * - 파일 쓰기 중단으로 인한 손상 방지
 * - 안전한 배포 롤아웃 가능
 *
 * ## 설정
 *
 * ```kotlin
 * scheduler:
 *   task-scheduler:
 *     pool-size: 3  # 기본값: 3
 * ```
 *
 * @see [Spring Issue #28241](https://github.com/spring-projects/spring-framework/issues/28241)
 * @see [Spring Scheduling Documentation](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SchedulerProperties::class, ShutdownProperties::class)
class SchedulerConfig {

    private val log = LoggerFactory.getLogger(SchedulerConfig::class.java)

    companion object {
        private val log = LoggerFactory.getLogger(SchedulerConfig::class.java)

        /** 로그 샘플링 간격: 1초에 1회만 WARN 로그 (log storm 방지) */
        private val REJECT_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1)

        /**
         * 인스턴스별 로그 샘플링 카운터
         *
         * 이 카운터들은 **로그 샘플링 용도**로, 정확한 클러스터 집계가 필요 없습니다:
         *
         * - 목적: log storm 방지 (1초에 1회만 WARN)
         * - 영향: 인스턴스별 독립 샘플링 → 정상 동작
         * - 결론: Micrometer Counter로 교체 불필요 (이미 rejected Counter 별도 존재)
         *
         * 실제 rejected 메트릭은 `scheduler.rejected` Counter로 Micrometer에 집계됩니다.
         */
        private val lastRejectLogNanos = AtomicLong(0)
        private val rejectedSinceLastLog = AtomicLong(0)

        /**
         * Scheduler용 AbortPolicy
         *
         * ### 왜 AbortPolicy인가?
         *
         * - **즉시 거부**: 큐 포화 시 즉시 예외 발생 → 모니터링 가능
         * - **메트릭 가시성**: rejected Counter로 Micrometer에 집계
         * - **CallerRuns 부적합**: 스케줄러는 호출자가 톰캣 스레드가 아니므로 무의미
         *
         * ### Log storm 방지
         *
         * 1초에 1회만 WARN 로그를 출력하여 로그 폭주를 방지합니다.
         */
        private val SCHEDULER_ABORT_POLICY = RejectedExecutionHandler { r, executor ->
            // 종료 중 거절은 정상 시나리오
            if (executor.isShutdown || executor.isTerminating) {
                throw RejectedExecutionException("Scheduler rejected (shutdown in progress)")
            }

            // 샘플링: 1초에 1회만 WARN 로그 (log storm 방지)
            val dropped = rejectedSinceLastLog.incrementAndGet()
            val now = System.nanoTime()
            val prev = lastRejectLogNanos.get()

            if (now - prev >= REJECT_LOG_INTERVAL_NANOS &&
                lastRejectLogNanos.compareAndSet(prev, now)) {
                val count = rejectedSinceLastLog.getAndSet(0)
                log.warn(
                    "[TaskScheduler] Task rejected (queue full). " +
                        "droppedInLastWindow={}, poolSize={}, activeCount={}, queueSize={}",
                    count,
                    executor.poolSize,
                    executor.activeCount,
                    executor.queue.size
                )
            }

            // P2 Fix: Log before throwing (previous log was after throw, unreachable)
            log.debug(
                "[TaskScheduler] Rejecting task - queue full: poolSize={}, activeCount={}, queueSize={}",
                executor.poolSize,
                executor.activeCount,
                executor.queue.size
            )

            throw RejectedExecutionException("TaskScheduler queue full (capacity exceeded)")
        }
    }

    /**
     * Spring TaskScheduler 빈
     *
     * ### 설정
     *
     * - **poolSize**: 3-4 스레드 (기본값 3, YAML 외부화)
     * - **threadNamePrefix**: "scheduler-"로 시작하는 명명된 스레드
     * - **waitForTasksToCompleteOnShutdown**: true (Graceful Shutdown)
     * - **awaitTerminationSeconds**: 60 (최대 대기 시간)
     *
     * ### Micrometer 메트릭
     *
     * - `scheduler.completed` - 완료된 작업 수
     * - `scheduler.active` - 현재 활성 스레드 수
     * - `scheduler.queued` - 큐에 대기 중인 작업 수
     * - `scheduler.rejected` - 거부된 작업 수 (커스텀)
     *
     * @return ThreadPoolTaskScheduler 인스턴스
     */
    @Bean
    @ConditionalOnMissingBean(name = ["taskScheduler"])
    fun taskScheduler(
        properties: SchedulerProperties,
        meterRegistry: MeterRegistry
    ): ThreadPoolTaskScheduler {
        // Context7 Best Practice: rejected Counter 등록 (ExecutorServiceMetrics 미제공)
        val schedulerRejectedCounter = Counter.builder("scheduler.rejected")
            .description("Number of scheduled tasks rejected due to queue full")
            .register(meterRegistry)

        val scheduler = ThreadPoolTaskScheduler()
        scheduler.setPoolSize(properties.poolSize)
        scheduler.setThreadNamePrefix("scheduler-")
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        scheduler.setAwaitTerminationSeconds(properties.awaitTerminationSeconds)

        // RejectedExecution 정책: AbortPolicy + 메트릭 기록
        scheduler.setRejectedExecutionHandler(RejectedExecutionHandler { r, e ->
            schedulerRejectedCounter.increment()
            SCHEDULER_ABORT_POLICY.rejectedExecution(r, e)
        })

        scheduler.initialize()

        // Context7 Best Practice: Micrometer ExecutorServiceMetrics 등록
        // 제공 메트릭: executor.completed, executor.active, executor.queued, executor.pool.size
        ExecutorServiceMetrics(
            scheduler.scheduledExecutor, "task.scheduler", Collections.emptyList()
        ).bindTo(meterRegistry)

        log.info("[TaskScheduler] Initialized with poolSize={}", properties.poolSize)

        return scheduler
    }
}
