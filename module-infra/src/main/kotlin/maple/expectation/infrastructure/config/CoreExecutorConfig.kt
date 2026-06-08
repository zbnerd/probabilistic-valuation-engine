package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.MeterRegistry
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
 * Core Executor Configuration — 모든 모듈이 공통으로 사용하는 Bean
 *
 * <p>Lightweight modules (external-api, synchronizer, calculator)은
 * 이 설정만 import하면 LogicExecutor 및 기본 executor를 사용할 수 있다.
 *
 * <p>포함 Bean:
 * <ul>
 *   <li>LogicExecutor, CheckedLogicExecutor, ExecutionPipeline
 *   <li>ExceptionTranslator, LoggingPolicy
 *   <li>contextPropagatingDecorator, taskDecoratorFactory
 *   <li>rejectionPolicyFactory, executorMetricsConfigurator
 *   <li>taskExecutor (기본 @Async용)
 * </ul>
 *
 * @see InfraExecutorConfig
 */
@Configuration
@EnableConfigurationProperties(ExecutorLoggingProperties::class, ExecutorProperties::class)
class CoreExecutorConfig(
    private val meterRegistry: MeterRegistry,
    private val executorProperties: ExecutorProperties,
) {

    private val log = LoggerFactory.getLogger(CoreExecutorConfig::class.java)

    init {
        executorProperties.validateAll()
        log.info(
            "[CoreExecutorConfig] ThreadPool configuration validated",
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

    @Bean
    @Primary
    @ConditionalOnMissingBean(LogicExecutor::class)
    fun logicExecutor(pipeline: ExecutionPipeline, translator: ExceptionTranslator): LogicExecutor = DefaultLogicExecutor(pipeline, translator)

    @Bean(name = ["checkedLogicExecutor"])
    @ConditionalOnMissingBean(CheckedLogicExecutor::class)
    fun checkedLogicExecutor(pipeline: ExecutionPipeline): CheckedLogicExecutor = DefaultCheckedLogicExecutor(pipeline)

    // ==================== Helper Factory Beans ====================

    @Bean
    fun contextPropagatingDecorator(): TaskDecorator = taskDecoratorFactory().createContextPropagatingDecorator()

    @Bean
    fun rejectionPolicyFactory(): RejectionPolicyFactory = RejectionPolicyFactory(meterRegistry)

    @Bean
    fun executorMetricsConfigurator(): ExecutorMetricsConfigurator = ExecutorMetricsConfigurator(meterRegistry)

    @Bean
    fun taskDecoratorFactory(): TaskDecoratorFactory = TaskDecoratorFactory()

    // ==================== Default TaskExecutor ====================

    @Bean(name = ["taskExecutor"])
    @ConditionalOnMissingBean(name = ["taskExecutor"])
    fun taskExecutor(contextPropagatingDecorator: TaskDecorator): java.util.concurrent.Executor {
        val config = executorProperties.async
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("async-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory().createAsyncAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator().registerExecutorMetrics(executor, "async")

        log.info("[CoreExecutorConfig] taskExecutor initialized: core={}, max={}, queue={}", config.corePoolSize, config.maxPoolSize, config.queueCapacity)
        return executor
    }
}
