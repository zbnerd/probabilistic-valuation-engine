package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Name
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Event Consumer Configuration - Priority-based thread pool separation.
 *
 * **Design Intent:**
 * * **Priority Isolation:** Separate virtual thread pools for HIGH/LOW priority events
 * * **Backpressure:** Semaphore limits prevent resource exhaustion
 * * **Virtual Threads:** Java 21 Loom for high-concurrency I/O-bound processing
 *
 * **Configuration (application.yml):**
 * ```yaml
 * event:
 *   consumer:
 *     high:
 *       max-concurrent: 50  # Default: 50 concurrent high-priority events
 *     low:
 *       max-concurrent: 20  # Default: 20 concurrent low-priority events
 * ```
 *
 * **SOLID Compliance:**
 * * **SRP:** Single responsibility - event consumer configuration
 * * **OCP:** Open for extension (properties), closed for modification
 *
 * @since 1.0.0
 * @see maple.expectation.event.HighPriorityEventConsumer
 * @see maple.expectation.event.LowPriorityEventConsumer
 */
@Configuration
@EnableConfigurationProperties(
    EventConsumerConfig.HighPriorityConsumerProperties::class,
    EventConsumerConfig.LowPriorityConsumerProperties::class,
)
class EventConsumerConfig {

    private val log = LoggerFactory.getLogger(EventConsumerConfig::class.java)
    private val highPriorityVtExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val lowPriorityVtExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Properties for high-priority event consumer.
     *
     * Externalized configuration via `event.consumer.high.*` prefix.
     */
    @ConfigurationProperties(prefix = "event.consumer.high")
    data class HighPriorityConsumerProperties(
        @Name("max-concurrent") val maxConcurrent: Int = 50,
    ) {
        init {
            if (maxConcurrent <= 0) {
                throw IllegalArgumentException("max-concurrent must be positive: $maxConcurrent")
            }
        }

        companion object {
            /** Default values */
            fun defaults() = HighPriorityConsumerProperties(50)
        }
    }

    /**
     * Properties for low-priority event consumer.
     *
     * Externalized configuration via `event.consumer.low.*` prefix.
     */
    @ConfigurationProperties(prefix = "event.consumer.low")
    data class LowPriorityConsumerProperties(
        @Name("max-concurrent") val maxConcurrent: Int = 20,
    ) {
        init {
            if (maxConcurrent <= 0) {
                throw IllegalArgumentException("max-concurrent must be positive: $maxConcurrent")
            }
        }

        companion object {
            /** Default values */
            fun defaults() = LowPriorityConsumerProperties(20)
        }
    }

    /**
     * High-priority event executor with semaphore backpressure.
     *
     * **Thread Pool:**
     * * Type: Virtual Thread Per Task Executor (Java 21)
     * * Backpressure: Semaphore with tryAcquire(5s) timeout
     * * Rejection: Fail-fast with RejectedExecutionException
     *
     * @param meterRegistry Micrometer registry
     * @param props Consumer properties from YAML
     * @return Wrapped executor with semaphore control
     */
    @Bean(name = ["highPriorityEventExecutor"])
    fun highPriorityEventExecutor(
        meterRegistry: MeterRegistry,
        props: HighPriorityConsumerProperties,
        logicExecutor: LogicExecutor,
    ): Executor {
        val semaphore = Semaphore(props.maxConcurrent)

        return Executor { runnable ->
            var acquired = false
            logicExecutor.executeWithFinally(
                {
                    acquired = semaphore.tryAcquire(5, TimeUnit.SECONDS)
                    if (!acquired) {
                        meterRegistry.counter("event.consumer.high.rejected").increment()
                        log.warn(
                            "[HighPriorityExecutor] Semaphore timeout - concurrent limit reached (limit={})",
                            props.maxConcurrent,
                        )
                        throw RejectedExecutionException("High priority event semaphore timeout")
                    }
                    highPriorityVtExecutor.execute(runnable)
                    null
                },
                Runnable {
                    if (acquired) {
                        semaphore.release()
                    }
                },
                TaskContext.of("EventConsumer", "HighPrioritySubmit"),
            )
        }
    }

    /**
     * Low-priority event executor with semaphore backpressure.
     *
     * **Thread Pool:**
     * * Type: Virtual Thread Per Task Executor (Java 21)
     * * Backpressure: Semaphore with tryAcquire(5s) timeout
     * * Rejection: Fail-fast with RejectedExecutionException
     *
     * @param meterRegistry Micrometer registry
     * @param props Consumer properties from YAML
     * @return Wrapped executor with semaphore control
     */
    @Bean(name = ["lowPriorityEventExecutor"])
    fun lowPriorityEventExecutor(
        meterRegistry: MeterRegistry,
        props: LowPriorityConsumerProperties,
        logicExecutor: LogicExecutor,
    ): Executor {
        val semaphore = Semaphore(props.maxConcurrent)

        return Executor { runnable ->
            var acquired = false
            logicExecutor.executeWithFinally(
                {
                    acquired = semaphore.tryAcquire(5, TimeUnit.SECONDS)
                    if (!acquired) {
                        meterRegistry.counter("event.consumer.low.rejected").increment()
                        log.warn(
                            "[LowPriorityExecutor] Semaphore timeout - concurrent limit reached (limit={})",
                            props.maxConcurrent,
                        )
                        throw RejectedExecutionException("Low priority event semaphore timeout")
                    }
                    lowPriorityVtExecutor.execute(runnable)
                    null
                },
                Runnable {
                    if (acquired) {
                        semaphore.release()
                    }
                },
                TaskContext.of("EventConsumer", "LowPrioritySubmit"),
            )
        }
    }

    @PreDestroy
    fun shutdownEventExecutors() {
        listOf(highPriorityVtExecutor, lowPriorityVtExecutor).forEach { es ->
            es.shutdown()
            if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[EventConsumerConfig] VT executor did not terminate in 5s, forcing shutdown")
                es.shutdownNow()
            }
        }
        log.info("[EventConsumerConfig] Event consumer executors shut down")
    }
}
