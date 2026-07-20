package maple.synchronizer.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
class SynchronizerExecutorConfiguration(
    private val meterRegistry: MeterRegistry,
    @Value("\${synchronizer.executor.vt-shutdown-timeout:PT5S}")
    private val vtShutdownTimeout: Duration,
) {
    private val ownedExecutors = mutableListOf<OwnedExecutor>()
    private val shutdown = AtomicBoolean(false)

    @Bean(name = ["kafkaResultChunkExecutor"], destroyMethod = "")
    fun kafkaResultChunkExecutor(): ExecutorService = createOwnedExecutor(
        name = "result",
        threadPrefix = "sync-result-chunk-",
    )

    @Bean(name = ["basicSnapshotChunkExecutor"], destroyMethod = "")
    fun basicSnapshotChunkExecutor(): ExecutorService = createOwnedExecutor(
        name = "basic",
        threadPrefix = "sync-basic-chunk-",
    )

    @Bean(name = ["synchronizerOcidLookupExecutor"])
    fun synchronizerOcidLookupExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 8
            maxPoolSize = 16
            queueCapacity = 200
            setThreadNamePrefix("async-")
            setAllowCoreThreadTimeOut(true)
            setKeepAliveSeconds(30)
            setTaskDecorator(SynchronizerMdcTaskDecorator())
            setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
        }

    @PreDestroy
    fun shutdownOwnedExecutors() {
        if (!shutdown.compareAndSet(false, true)) return

        val executors = synchronized(ownedExecutors) { ownedExecutors.toList() }
        executors.forEach { it.service.shutdown() }
        executors.forEach(::awaitOrForce)
        log.info("Synchronizer virtual-thread executors shut down: count={}", executors.size)
    }

    private fun createOwnedExecutor(
        name: String,
        threadPrefix: String,
    ): ExecutorService {
        val service = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name(threadPrefix, 0).factory(),
        )
        synchronized(ownedExecutors) { ownedExecutors.add(OwnedExecutor(name, service)) }
        return service
    }

    private fun awaitOrForce(owned: OwnedExecutor) {
        val terminated = runCatching {
            owned.service.awaitTermination(vtShutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)
        }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                log.warn(
                    "Synchronizer executor shutdown wait failed: executor={}",
                    owned.name,
                    failure,
                )
                false
            },
        )
        if (terminated) return

        owned.service.shutdownNow()
        Counter.builder("etl.executor.forced.shutdown")
            .tags("module", "synchronizer", "executor", owned.name)
            .register(meterRegistry)
            .increment()
        log.warn(
            "Synchronizer executor did not terminate before timeout; forced shutdown: executor={} timeout={}",
            owned.name,
            vtShutdownTimeout,
        )
    }

    private data class OwnedExecutor(
        val name: String,
        val service: ExecutorService,
    )

    private companion object {
        private val log = LoggerFactory.getLogger(SynchronizerExecutorConfiguration::class.java)
    }
}
