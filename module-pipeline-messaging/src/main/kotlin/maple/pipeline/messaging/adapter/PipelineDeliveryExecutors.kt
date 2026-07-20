package maple.pipeline.messaging.adapter

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import maple.pipeline.messaging.metrics.DeliveryMetrics

class PipelineDeliveryExecutors(
    private val metrics: DeliveryMetrics,
    val retryScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("pipeline-retry-", 0L).factory(),
    ),
    val deliveryExecutor: ExecutorService = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("pipeline-delivery-", 0L).factory(),
    ),
    val dltExecutor: ExecutorService = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("pipeline-dlt-", 0L).factory(),
    ),
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        retryScheduler.shutdown()
        deliveryExecutor.shutdown()
        dltExecutor.shutdown()
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_SECONDS)
        awaitOrForce("retry", retryScheduler, deadlineNanos)
        awaitOrForce("delivery", deliveryExecutor, deadlineNanos)
        awaitOrForce("dlt", dltExecutor, deadlineNanos)
    }

    private fun awaitOrForce(resource: String, executor: ExecutorService, deadlineNanos: Long) {
        val remainingNanos = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
        val termination = runCatching { executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS) }
        termination.exceptionOrNull()
            ?.takeIf { it is InterruptedException }
            ?.let { Thread.currentThread().interrupt() }
        if (!termination.getOrDefault(false)) {
            executor.shutdownNow()
            metrics.recordForcedShutdown(resource)
        }
    }

    companion object {
        private const val SHUTDOWN_SECONDS = 10L
    }
}
