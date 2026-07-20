package maple.externalapi.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ExternalApiExecutorConfiguration(
    private val meterRegistry: MeterRegistry,
    @Value("\${external-api.executor.shutdown-timeout:PT5S}")
    private val shutdownTimeout: Duration,
) {
    private val ownedExecutors = mutableListOf<OwnedExecutor>()
    private val shutdown = AtomicBoolean(false)

    @Bean(name = ["internalApiExecutor"], destroyMethod = "")
    fun internalApiExecutor(): ExecutorService = createOwnedExecutor(
        name = "internal",
        threadPrefix = "external-internal-",
    )

    @Bean(name = ["urgentCharacterRequestExecutor"], destroyMethod = "")
    fun urgentCharacterRequestExecutor(): ExecutorService = createOwnedExecutor(
        name = "urgent",
        threadPrefix = "external-urgent-",
    )

    @PreDestroy
    fun shutdownOwnedExecutors() {
        if (!shutdown.compareAndSet(false, true)) return

        val executors = synchronized(ownedExecutors) { ownedExecutors.toList() }
        executors.forEach { it.service.shutdown() }
        executors.forEach(::awaitOrForce)
        log.info("External API executors shut down: count={}", executors.size)
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
            owned.service.awaitTermination(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)
        }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                log.warn(
                    "External API executor shutdown wait failed: executor={}",
                    owned.name,
                    failure,
                )
                false
            },
        )
        if (terminated) return

        owned.service.shutdownNow()
        Counter.builder("etl.executor.forced.shutdown")
            .tags("module", "external-api", "executor", owned.name)
            .register(meterRegistry)
            .increment()
        log.warn(
            "External API executor did not terminate before timeout; forced shutdown: executor={} timeout={}",
            owned.name,
            shutdownTimeout,
        )
    }

    private data class OwnedExecutor(
        val name: String,
        val service: ExecutorService,
    )

    private companion object {
        private val log = LoggerFactory.getLogger(ExternalApiExecutorConfiguration::class.java)
    }
}
