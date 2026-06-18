package maple.externalapi.loop

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Loop executor configuration (Issue #1291).
 *
 * Dedicated virtual-thread pool for PhaseLoopController iterations. Separate
 * from ExternalApiScheduler's inline newVirtualThreadPerTaskExecutor (daily +
 * per-phase triggers) so a slow loop iteration can never starve the scheduler's
 * submit path, and so @PreDestroy can drain the pool independently of the
 * scheduler's lifecycle.
 */
@Configuration
class LoopExecutorConfig {

    private val log = LoggerFactory.getLogger(LoopExecutorConfig::class.java)

    @Bean(name = ["loopExecutor"])
    fun loopExecutor(
        @Value("\${external-api.loop.executor.core-pool-size:4}") corePoolSize: Int,
        @Value("\${external-api.loop.executor.max-pool-size:16}") maxPoolSize: Int,
        @Value("\${external-api.loop.executor.queue-capacity:64}") queueCapacity: Int,
        @Value("\${external-api.loop.executor.thread-name-prefix:ext-api-loop-}") threadNamePrefix: String,
        @Value("\${external-api.loop.executor.virtual-threads:true}") virtualThreads: Boolean,
        @Value("\${external-api.loop.executor.await-termination-seconds:30}") awaitTerminationSeconds: Int,
    ): AsyncTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = corePoolSize
        executor.maxPoolSize = maxPoolSize
        executor.queueCapacity = queueCapacity
        executor.setThreadNamePrefix(threadNamePrefix)
        if (virtualThreads) {
            executor.setVirtualThreads(virtualThreads)
        }
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds)
        executor.initialize()
        log.info(
            "[LoopExecutorConfig] loopExecutor initialized: core={}, max={}, queue={}, virtual={}",
            corePoolSize, maxPoolSize, queueCapacity, virtualThreads,
        )
        return executor
    }
}
