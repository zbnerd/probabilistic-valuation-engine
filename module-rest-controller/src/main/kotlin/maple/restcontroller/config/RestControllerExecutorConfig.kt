package maple.restcontroller.config

import java.util.concurrent.Executor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
class RestControllerExecutorConfig {

    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 4
        executor.maxPoolSize = 8
        executor.queueCapacity = 100
        executor.setThreadNamePrefix("rest-api-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)
        executor.initialize()
        return executor
    }
}
