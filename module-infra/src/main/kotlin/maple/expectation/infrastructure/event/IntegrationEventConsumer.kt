package maple.expectation.infrastructure.event

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.VirtualThreadExecutorManager
import org.slf4j.LoggerFactory

/**
 * Priority-aware async event consumer with semaphore-bounded concurrency.
 *
 * @param priority label used for metrics and logging ("high" or "low")
 * @param maxConcurrent maximum concurrent event processing
 */
open class IntegrationEventConsumer(
    private val priority: String,
    private val maxConcurrent: Int,
    private val logicExecutor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) {
    private val logPrefix = "${priority.replaceFirstChar { it.uppercase() }}PriorityConsumer"
    private val logger = LoggerFactory.getLogger(IntegrationEventConsumer::class.java)
    private val exec = VirtualThreadExecutorManager("IntegrationEventConsumer")
    private val semaphore = Semaphore(maxConcurrent)

    fun <T> processAsync(event: IntegrationEvent<T>, handler: EventHandler<T>) {
        var acquired = false
        try {
            acquired = semaphore.tryAcquire(5, TimeUnit.SECONDS)
            if (!acquired) {
                meterRegistry.counter("event.consumer.$priority.rejected").increment()
                logger.warn(
                    "[$logPrefix] Semaphore timeout - concurrent limit reached (limit={})",
                    maxConcurrent,
                )
                throw RejectedExecutionException("$priority priority event semaphore timeout")
            }

            exec.executor.execute {
                val start = System.nanoTime()
                logicExecutor.executeWithFinally(
                    {
                        handler.handle(event.payload)
                        meterRegistry.counter("event.consumer.$priority.processed").increment()
                        null
                    },
                    Runnable {
                        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                        meterRegistry.timer("event.consumer.$priority.duration")
                            .record(durationMs, TimeUnit.MILLISECONDS)
                    },
                    TaskContext.of("${priority.replaceFirstChar { it.uppercase() }}PriorityEvent", event.eventType, event.eventId),
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RejectedExecutionException("$priority priority event consumer interrupted", e)
        } finally {
            if (acquired) {
                semaphore.release()
            }
        }
    }

    fun interface EventHandler<T> {
        fun handle(payload: T)
    }

    @PreDestroy
    fun shutdown() = exec.shutdown()
}
