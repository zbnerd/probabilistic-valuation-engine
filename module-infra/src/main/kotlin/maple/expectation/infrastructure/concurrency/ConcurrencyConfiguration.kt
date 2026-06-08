package maple.expectation.infrastructure.concurrency

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
class ConcurrencyConfiguration {

    @Bean
    fun executorRegistry(): ExecutorRegistry {
        val map = mapOf(
            ExecutorQualifier.CALCULATION to namedExecutor("calc", 4, 8),
            ExecutorQualifier.IO to namedExecutor("io", 8, 16),
            ExecutorQualifier.SCHEDULER to namedExecutor("scheduler", 2, 4),
            ExecutorQualifier.CHUNK to namedExecutor("chunk", 2, 4),
            ExecutorQualifier.BACKFILL to namedExecutor("backfill", 2, 4)
        )
        return ExecutorRegistry(map)
    }

    @Bean
    fun executorSelector(registry: ExecutorRegistry): ExecutorSelector =
        DefaultExecutorSelector(registry)

    @Bean
    fun threadLauncher(registry: ExecutorRegistry): ThreadLauncher =
        DefaultThreadLauncher(registry.get(ExecutorQualifier.BACKFILL))

    @Bean
    fun backpressureLimiter(): BackpressureLimiter =
        DefaultBackpressureLimiter(permits = 16, component = "default")

    @Bean
    fun asyncGuard(): AsyncGuard = DefaultAsyncGuard()

    private fun namedExecutor(name: String, core: Int, max: Int): ThreadPoolTaskExecutor {
        val e = ThreadPoolTaskExecutor()
        e.corePoolSize = core
        e.maxPoolSize = max
        e.queueCapacity = 64
        e.setThreadNamePrefix("$name-")
        e.setWaitForTasksToCompleteOnShutdown(true)
        e.setAwaitTerminationSeconds(10)
        e.initialize()
        return e
    }
}
