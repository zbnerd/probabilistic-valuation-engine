package maple.expectation.infrastructure.event

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.domain.event.IntegrationEvent
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@Component
class LowPriorityEventConsumer(
    private val logicExecutor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    @Value("\${event.consumer.low.max-concurrent:20}") private val maxConcurrent: Int
) {
    private val logger = LoggerFactory.getLogger(LowPriorityEventConsumer::class.java)
    private val executor: Executor = Executors.newVirtualThreadPerTaskExecutor()
    private val semaphore = Semaphore(maxConcurrent)

    fun <T> processAsync(event: IntegrationEvent<T>, handler: EventHandler<T>) {
        var acquired = false
        try {
            acquired = semaphore.tryAcquire(5, TimeUnit.SECONDS)
            if (!acquired) {
                meterRegistry.counter("event.consumer.low.rejected").increment()
                logger.warn(
                    "[LowPriorityConsumer] Semaphore timeout - concurrent limit reached (limit={})",
                    maxConcurrent
                )
                throw RejectedExecutionException("Low priority event semaphore timeout")
            }

            executor.execute {
                logicExecutor.executeVoid(
                    {
                        val start = System.nanoTime()
                        try {
                            handler.handle(event.payload)
                            meterRegistry.counter("event.consumer.low.processed").increment()
                        } finally {
                            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                            meterRegistry.timer("event.consumer.low.duration")
                                .record(durationMs, TimeUnit.MILLISECONDS)
                        }
                    },
                    TaskContext.of("LowPriorityEvent", event.eventType, event.eventId)
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RejectedExecutionException("Low priority event consumer interrupted", e)
        } finally {
            if (acquired) {
                semaphore.release()
            }
        }
    }

    fun interface EventHandler<T> {
        fun handle(payload: T)
    }
}
