package maple.expectation.infrastructure.event

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class HighPriorityEventConsumer(
    private val logicExecutor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    @Value("\${event.consumer.high.max-concurrent:50}") private val maxConcurrent: Int,
) {
    private val logger = LoggerFactory.getLogger(HighPriorityEventConsumer::class.java)
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val semaphore = Semaphore(maxConcurrent)

    fun <T> processAsync(event: IntegrationEvent<T>, handler: EventHandler<T>) {
        // [Semaphore Lifecycle Exception] try-catch-finally required for semaphore acquire/release:
        // 1. Semaphore.tryAcquire() throws InterruptedException — must restore interrupt flag
        // 2. Semaphore.release() must run in finally regardless of success/failure
        // 3. LogicExecutor cannot manage this because the semaphore is acquired *before* delegation
        var acquired = false
        try {
            acquired = semaphore.tryAcquire(5, TimeUnit.SECONDS)
            if (!acquired) {
                meterRegistry.counter("event.consumer.high.rejected").increment()
                logger.warn(
                    "[HighPriorityConsumer] Semaphore timeout - concurrent limit reached (limit={})",
                    maxConcurrent,
                )
                throw RejectedExecutionException("High priority event semaphore timeout")
            }

            executor.execute {
                val start = System.nanoTime()
                logicExecutor.executeWithFinally(
                    {
                        handler.handle(event.payload)
                        meterRegistry.counter("event.consumer.high.processed").increment()
                        null
                    },
                    Runnable {
                        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                        meterRegistry.timer("event.consumer.high.duration")
                            .record(durationMs, TimeUnit.MILLISECONDS)
                    },
                    TaskContext.of("HighPriorityEvent", event.eventType, event.eventId),
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RejectedExecutionException("High priority event consumer interrupted", e)
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
    fun shutdown() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            logger.warn("[HighPriorityEventConsumer] VT executor did not terminate in 5s")
            executor.shutdownNow()
        }
        logger.info("[HighPriorityEventConsumer] Executor shut down")
    }
}
