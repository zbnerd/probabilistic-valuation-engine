package maple.expectation.infrastructure.config

import java.util.concurrent.Executor
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * RestController 전용 Executor Configuration — module-rest-controller 한정
 *
 * <p>Issue #1126: CoreExecutorConfig.taskExecutor 와의 bean 이름 충돌 해결을 위해
 * 명시적 `restApiControllerExecutor` bean 이름 사용.
 *
 * <p>module-rest-controller 만 이 설정을 import 한다.
 * module-app 등 full-stack 모듈은 {@link ExecutorConfig} 통해 {@link CoreExecutorConfig} +
 * {@link InfraExecutorConfig} 만 import.
 *
 * <p>포함 Bean:
 * <ul>
 *   <li>restApiControllerExecutor (RestController dispatch용)
 * </ul>
 *
 * @see CoreExecutorConfig
 * @see InfraExecutorConfig
 */
@Configuration
@EnableConfigurationProperties(ExecutorProperties::class)
class RestControllerExecutorConfig(
    private val executorProperties: ExecutorProperties,
) {

    private val log = LoggerFactory.getLogger(RestControllerExecutorConfig::class.java)

    @Bean(name = ["restApiControllerExecutor"])
    @ConditionalOnMissingBean(name = ["restApiControllerExecutor"])
    fun restApiControllerExecutor(
        contextPropagatingDecorator: TaskDecorator,
        rejectionPolicyFactory: RejectionPolicyFactory,
        executorMetricsConfigurator: ExecutorMetricsConfigurator,
    ): Executor {
        val config = executorProperties.async
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("rest-api-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory.createAsyncAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator.registerExecutorMetrics(executor, "rest.api")

        log.info(
            "[RestControllerExecutorConfig] restApiControllerExecutor initialized: core={}, max={}, queue={}",
            config.corePoolSize,
            config.maxPoolSize,
            config.queueCapacity,
        )
        return executor
    }
}
