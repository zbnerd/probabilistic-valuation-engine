package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import maple.expectation.infrastructure.executor.CheckedLogicExecutor
import maple.expectation.infrastructure.executor.DefaultCheckedLogicExecutor
import maple.expectation.infrastructure.executor.DefaultLogicExecutor
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.policy.ExecutionPipeline
import maple.expectation.infrastructure.executor.policy.ExecutionPolicy
import maple.expectation.infrastructure.executor.policy.LoggingPolicy
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.annotation.AnnotationAwareOrderComparator
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Executor Configuration - 비동기 실행 및 Thread Pool 관리 설정
 *
 * <h4>책임 (Refactoring 후)</h4>
 *
 * <ul>
 *   <li><b>LogicExecutor Beans</b>: logicExecutor, checkedLogicExecutor, executionPipeline
 *   <li><b>ThreadPoolTaskExecutor Beans</b>: alertTaskExecutor, aiTaskExecutor,
 *       expectationComputeExecutor, taskExecutor
 *   <li><b>조정</b>: RejectionPolicyFactory, ExecutorMetricsConfigurator, TaskDecoratorFactory를 활용
 * </ul>
 *
 * <h4>분리된 책임 (별도 클래스)</h4>
 *
 * <ul>
 *   <li>{@link RejectionPolicyFactory}: Rejection Policy 생성 (LOGGING_ABORT_POLICY,
 *       EXPECTATION_ABORT_POLICY)
 *   <li>{@link ExecutorMetricsConfigurator}: Micrometer 메트릭 등록
 *   <li>{@link TaskDecoratorFactory}: MDC + Cache Context 전파용 TaskDecorator 생성
 * </ul>
 *
 * <h4>P2-25 표준화</h4>
 *
 * <p>모든 ThreadPoolTaskExecutor는 {@link ExecutorProperties}를 통해 중앙 집중식 관리됩니다.
 * corePoolSize:maxPoolSize는 항상 1:2 비율을 유지해야 합니다.
 */
@Configuration
@EnableConfigurationProperties(ExecutorLoggingProperties::class, ExecutorProperties::class, MicroBatchWriterProperties::class)
class ExecutorConfig(
    private val meterRegistry: MeterRegistry,
    private val executorProperties: ExecutorProperties,
) {

    private val log = LoggerFactory.getLogger(ExecutorConfig::class.java)

    init {
        // P2-25: 시작 시점에 1:2 비율 검증
        executorProperties.validateAll()
        log.info(
            "[ExecutorConfig] P2-25 ThreadPool configuration validated: equipment={}/{}, preset={}/{}, alert={}/{}, expectation={}/{}, async={}/{}, operational={}/{}, backfill={}/{}",
            executorProperties.equipment.corePoolSize, executorProperties.equipment.maxPoolSize,
            executorProperties.preset.corePoolSize, executorProperties.preset.maxPoolSize,
            executorProperties.alert.corePoolSize, executorProperties.alert.maxPoolSize,
            executorProperties.expectation.corePoolSize, executorProperties.expectation.maxPoolSize,
            executorProperties.async.corePoolSize, executorProperties.async.maxPoolSize,
            executorProperties.operational.corePoolSize, executorProperties.operational.maxPoolSize,
            executorProperties.backfill.corePoolSize, executorProperties.backfill.maxPoolSize,
        )
    }

    // ==================== LogicExecutor Beans ====================

    @Bean
    fun exceptionTranslator(): ExceptionTranslator = ExceptionTranslator.defaultTranslator()

    @Bean
    fun loggingPolicy(props: ExecutorLoggingProperties): LoggingPolicy = LoggingPolicy(props.slowMs)

    @Bean
    @ConditionalOnMissingBean(ExecutionPipeline::class)
    fun executionPipeline(policies: List<ExecutionPolicy>): ExecutionPipeline {
        val ordered = ArrayList(policies)
        AnnotationAwareOrderComparator.sort(ordered)
        return ExecutionPipeline(ordered)
    }

    /**
     * 비즈니스 레이어 기본 Executor (Primary)
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(LogicExecutor::class)
    fun logicExecutor(pipeline: ExecutionPipeline, translator: ExceptionTranslator): LogicExecutor = DefaultLogicExecutor(pipeline, translator)

    /**
     * IO 경계 전용 CheckedLogicExecutor 빈 등록
     */
    @Bean(name = ["checkedLogicExecutor"])
    @ConditionalOnMissingBean(CheckedLogicExecutor::class)
    fun checkedLogicExecutor(pipeline: ExecutionPipeline): CheckedLogicExecutor = DefaultCheckedLogicExecutor(pipeline)

    // ==================== TaskDecorator Bean ====================

    @Bean
    fun contextPropagatingDecorator(): TaskDecorator = taskDecoratorFactory().createContextPropagatingDecorator()

    // ==================== ThreadPoolTaskExecutor Beans ====================

    /**
     * 외부 알림(Discord/Slack 등) 전용 비동기 Executor
     */
    @Bean(name = ["alertTaskExecutor"])
    @ConditionalOnMissingBean(name = ["alertTaskExecutor"])
    fun alertTaskExecutor(contextPropagatingDecorator: TaskDecorator): Executor {
        val config = executorProperties.alert
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("alert-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory().createAlertAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(false)

        executor.initialize()
        executorMetricsConfigurator().registerExecutorMetrics(executor, "alert")

        log.info(
            "[ExecutorConfig] alertTaskExecutor initialized: core={}, max={}, queue={}",
            config.corePoolSize,
            config.maxPoolSize,
            config.queueCapacity,
        )

        return executor
    }

    /**
     * AI LLM 호출 전용 Executor (Issue #283 P0-5: Semaphore 제한 외부화)
     */
    @Bean(name = ["aiTaskExecutor"])
    fun aiTaskExecutor(
        @org.springframework.beans.factory.annotation.Value("\${ai.sre.max-concurrent-threads:10}")
        maxConcurrent: Int,
    ): Executor {
        val semaphore = Semaphore(maxConcurrent)
        val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()

        return Executor { runnable ->
            virtualThreadExecutor.execute {
                var acquired = false
                try {
                    acquired = semaphore.tryAcquire(10, TimeUnit.SECONDS)
                    if (!acquired) {
                        log.warn(
                            "[AiTaskExecutor] Semaphore timeout - LLM 호출 동시성 한도 초과 (limit={})",
                            maxConcurrent,
                        )
                        throw RejectedExecutionException("AI task executor semaphore timeout")
                    }
                    runnable.run()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw RejectedExecutionException("AI task executor interrupted", e)
                } finally {
                    if (acquired) {
                        semaphore.release()
                    }
                }
            }
        }
    }

    /**
     * Async Executor for CompletableFuture operations (ADR-039)
     */
    @Bean(name = ["asyncExecutor"])
    fun asyncExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Default TaskExecutor for @Async methods (Unit 1: P2 Technical Debt)
     */
    @Bean(name = ["taskExecutor"])
    @ConditionalOnMissingBean(name = ["taskExecutor"])
    fun taskExecutor(contextPropagatingDecorator: TaskDecorator): Executor {
        val config = executorProperties.async
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("async-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator().registerExecutorMetrics(executor, "async")

        log.info(
            "[ExecutorConfig] taskExecutor initialized: core={}, max={}, queue={}",
            config.corePoolSize,
            config.maxPoolSize,
            config.queueCapacity,
        )

        return executor
    }

    /**
     * Expectation compute(파싱/계산/외부 호출 포함) 데드라인 강제를 위한 전용 Executor
     */
    @Bean(name = ["expectationComputeExecutor"])
    @ConditionalOnMissingBean(name = ["expectationComputeExecutor"])
    fun expectationComputeExecutor(contextPropagatingDecorator: TaskDecorator): Executor {
        val config = executorProperties.expectation
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("expectation-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory().createExpectationAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator().registerExecutorMetrics(executor, "expectation.compute")

        log.info(
            "[ExecutorConfig] expectationComputeExecutor initialized: core={}, max={}, queue={}",
            config.corePoolSize,
            config.maxPoolSize,
            config.queueCapacity,
        )

        return executor
    }

    /**
     * Operational Executor for real-time user requests (Issue #617 US-004)
     *
     * <p>Prevents starvation from backfill operations by maintaining a separate pool.
     * Uses the same configuration as the equipment executor for consistency.
     */
    @Bean(name = ["operationalExecutor"])
    @ConditionalOnMissingBean(name = ["operationalExecutor"])
    fun operationalExecutor(contextPropagatingDecorator: TaskDecorator): Executor {
        val config = executorProperties.operational
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("operational-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory().createAlertAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator().registerExecutorMetrics(executor, "operational")

        log.info(
            "[ExecutorConfig] operationalExecutor initialized: core={}, max={}, queue={}",
            config.corePoolSize,
            config.maxPoolSize,
            config.queueCapacity,
        )

        return executor
    }

    /**
     * Backfill Executor for batch/background operations (Issue #617 US-004)
     *
     * <p>Separate pool with smaller capacity (4:8) but larger queue (500) to prevent
     * starvation of operational threads. Uses CallerRunsPolicy for backpressure.
     * Longer shutdown timeout (60s) to allow batch operations to complete gracefully.
     */
    @Bean(name = ["backfillExecutor"])
    @ConditionalOnMissingBean(name = ["backfillExecutor"])
    fun backfillExecutor(contextPropagatingDecorator: TaskDecorator): Executor {
        val config = executorProperties.backfill
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("backfill-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(60)

        executor.initialize()
        executorMetricsConfigurator().registerExecutorMetrics(executor, "backfill")

        log.info(
            "[ExecutorConfig] backfillExecutor initialized: core={}, max={}, queue={}",
            config.corePoolSize,
            config.maxPoolSize,
            config.queueCapacity,
        )

        return executor
    }

    // ==================== Helper Factory Beans ====================

    @Bean
    fun rejectionPolicyFactory(): RejectionPolicyFactory = RejectionPolicyFactory(meterRegistry)

    @Bean
    fun executorMetricsConfigurator(): ExecutorMetricsConfigurator = ExecutorMetricsConfigurator(meterRegistry)

    @Bean
    fun taskDecoratorFactory(): TaskDecoratorFactory = TaskDecoratorFactory()
}
