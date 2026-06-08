package maple.expectation.infrastructure.config

import jakarta.annotation.PreDestroy
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Infra Executor Configuration — module-app 전용 heavier thread pool beans
 *
 * <p>Lightweight modules은 필요하지 않은 executor beans.
 * module-app만 import하거나 ExecutorConfig를 통해 간접 import.
 *
 * <p>포함 Bean:
 * <ul>
 *   <li>alertTaskExecutor, aiTaskExecutor, asyncExecutor (VT)
 *   <li>expectationComputeIoExecutor, expectationComputeCpuExecutor, operationalExecutor, backfillExecutor
 * </ul>
 *
 * @see CoreExecutorConfig
 */
@Configuration
@Import(CoreExecutorConfig::class)
@EnableConfigurationProperties(ExecutorProperties::class, MicroBatchWriterProperties::class)
class InfraExecutorConfig(
    private val executorProperties: ExecutorProperties,
) {

    private val log = LoggerFactory.getLogger(InfraExecutorConfig::class.java)
    private val asyncVtExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val aiVtExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    @Bean(name = ["alertTaskExecutor"])
    @ConditionalOnMissingBean(name = ["alertTaskExecutor"])
    fun alertTaskExecutor(contextPropagatingDecorator: TaskDecorator, rejectionPolicyFactory: RejectionPolicyFactory, executorMetricsConfigurator: ExecutorMetricsConfigurator): Executor {
        val config = executorProperties.alert
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("alert-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory.createAlertAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(false)

        executor.initialize()
        executorMetricsConfigurator.registerExecutorMetrics(executor, "alert")

        log.info("[InfraExecutorConfig] alertTaskExecutor initialized: core={}, max={}, queue={}", config.corePoolSize, config.maxPoolSize, config.queueCapacity)
        return executor
    }

    @Bean(name = ["aiTaskExecutor"])
    fun aiTaskExecutor(
        rejectionPolicyFactory: RejectionPolicyFactory,
        @Value("\${ai.sre.max-concurrent-threads:10}") maxConcurrent: Int,
    ): Executor {
        val semaphore = Semaphore(maxConcurrent)

        return Executor { runnable ->
            aiVtExecutor.execute {
                var acquired = false
                try {
                    acquired = semaphore.tryAcquire(10, TimeUnit.SECONDS)
                    if (!acquired) {
                        log.warn("[AiTaskExecutor] Semaphore timeout - LLM 호출 동시성 한도 초과 (limit={})", maxConcurrent)
                        throw RejectedExecutionException("AI task executor semaphore timeout")
                    }
                    runnable.run()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw RejectedExecutionException("AI task executor interrupted", e)
                } finally {
                    if (acquired) semaphore.release()
                }
            }
        }
    }

    @Bean(name = ["asyncExecutor"])
    fun asyncExecutor(): ExecutorService = asyncVtExecutor

    @Bean(name = ["expectationComputeIoExecutor"])
    @ConditionalOnMissingBean(name = ["expectationComputeIoExecutor"])
    fun expectationComputeIoExecutor(contextPropagatingDecorator: TaskDecorator, rejectionPolicyFactory: RejectionPolicyFactory, executorMetricsConfigurator: ExecutorMetricsConfigurator): Executor {
        val config = executorProperties.expectation.computeIo
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("expectation-io-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory.createExpectationAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator.registerExecutorMetrics(executor, "expectation.compute-io")

        log.info("[InfraExecutorConfig] expectationComputeIoExecutor initialized: core={}, max={}, queue={}", config.corePoolSize, config.maxPoolSize, config.queueCapacity)
        return executor
    }

    @Bean(name = ["expectationComputeCpuExecutor"])
    @ConditionalOnMissingBean(name = ["expectationComputeCpuExecutor"])
    fun expectationComputeCpuExecutor(contextPropagatingDecorator: TaskDecorator, rejectionPolicyFactory: RejectionPolicyFactory, executorMetricsConfigurator: ExecutorMetricsConfigurator): Executor {
        val config = executorProperties.expectation.computeCpu
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("expectation-cpu-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory.createExpectationAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator.registerExecutorMetrics(executor, "expectation.compute-cpu")

        log.info("[InfraExecutorConfig] expectationComputeCpuExecutor initialized: core={}, max={}, queue={}", config.corePoolSize, config.maxPoolSize, config.queueCapacity)
        return executor
    }

    @Bean(name = ["operationalExecutor"])
    @ConditionalOnMissingBean(name = ["operationalExecutor"])
    fun operationalExecutor(contextPropagatingDecorator: TaskDecorator, rejectionPolicyFactory: RejectionPolicyFactory, executorMetricsConfigurator: ExecutorMetricsConfigurator): Executor {
        val config = executorProperties.operational
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("operational-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory.createAlertAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator.registerExecutorMetrics(executor, "operational")

        log.info("[InfraExecutorConfig] operationalExecutor initialized: core={}, max={}, queue={}", config.corePoolSize, config.maxPoolSize, config.queueCapacity)
        return executor
    }

    @Bean(name = ["backfillExecutor"])
    @ConditionalOnMissingBean(name = ["backfillExecutor"])
    fun backfillExecutor(contextPropagatingDecorator: TaskDecorator, rejectionPolicyFactory: RejectionPolicyFactory, executorMetricsConfigurator: ExecutorMetricsConfigurator): Executor {
        val config = executorProperties.backfill
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("backfill-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory.createBackfillAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(60)

        executor.initialize()
        executorMetricsConfigurator.registerExecutorMetrics(executor, "backfill")

        log.info("[InfraExecutorConfig] backfillExecutor initialized: core={}, max={}, queue={}", config.corePoolSize, config.maxPoolSize, config.queueCapacity)
        return executor
    }

    @PreDestroy
    fun shutdownVirtualThreadExecutors() {
        listOf(asyncVtExecutor, aiVtExecutor).forEach { es ->
            es.shutdown()
            if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[InfraExecutorConfig] VT executor did not terminate in 5s, forcing shutdown")
                es.shutdownNow()
            }
        }
        log.info("[InfraExecutorConfig] Virtual thread executors shut down")
    }
}
