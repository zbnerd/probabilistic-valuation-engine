package maple.expectation.infrastructure.admission

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 🔥 PRODUCTION-READY: Simple Global Admission Control
 *
 * <h3>Purpose</h3>
 * Prevents CPU saturation from unique-key fan-out by limiting concurrent cold misses.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li>Real Bounded Queue: ArrayBlockingQueue (not just counter)</li>
 *   <li>Worker Pool Pattern: Worker threads consume queue (no HTTP thread blocking)</li>
 *   <li>Backpressure: Queue full → Fast reject (no infinite waiting)</li>
 *   <li>Semaphore: Limits in-flight executions</li>
 * </ul>
 *
 * <h3>Key Fix</h3>
 * Before: HTTP threads blocked on semaphore.tryAcquire() → Thread pool exhaustion
 * After: HTTP threads return immediately, worker threads handle queue → Stable
 *
 * @param maxInFlight Maximum concurrent cold-path calculations
 * @param maxQueueSize Maximum queue size
 * @param workerSize Worker pool size
 * @param meterRegistry Micrometer registry
 */
@Component
class SimpleAdmissionControl(
    @Value("\${admission-control.max-in-flight:100}")
    private val maxInFlight: Int,

    @Value("\${admission-control.max-queue-size:1000}")
    private val maxQueueSize: Int,

    @Value("\${admission-control.worker-pool-size:16}")
    private val workerSize: Int,

    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(SimpleAdmissionControl::class.java)

    // 🔥 REAL BOUNDED QUEUE
    private val queue = ArrayBlockingQueue<AdmissionTask<*>>(maxQueueSize)

    // Semaphore limits IN-FLIGHT (executing) requests
    private val semaphore = Semaphore(maxInFlight)

    // 🔥 WORKER POOL
    private val executor = Executors.newFixedThreadPool(workerSize) { runnable ->
        Thread(runnable, "admission-worker").apply { isDaemon = true }
    }

    // 🔥 METRICS
    private val inFlight = AtomicInteger(0)
    private val queueSize = AtomicInteger(0)
    private val queueFullCounter: Counter
    private val timeoutCounter: Counter

    init {
        queueFullCounter = Counter.builder("admission_control.queue.full")
            .description("Queue was full when request arrived")
            .register(meterRegistry)

        timeoutCounter = Counter.builder("admission_control.queue.timeout")
            .description("Requests timed out waiting in queue")
            .register(meterRegistry)

        Gauge.builder("admission_control.in_flight", inFlight) { it.get().toDouble() }
            .description("Currently executing requests")
            .register(meterRegistry)

        Gauge.builder("admission_control.queue_depth", queueSize) { it.get().toDouble() }
            .description("Requests waiting in queue")
            .register(meterRegistry)

        // 🔥 Start worker pool
        repeat(workerSize) {
            executor.submit { workerLoop() }
        }

        log.info(
            "[SimpleAdmissionControl] Initialized: maxInFlight={}, maxQueueSize={}, workerSize={}",
            maxInFlight,
            maxQueueSize,
            workerSize
        )
    }

    /**
     * 🔥 SUBMIT: Non-blocking submit with fast reject
     *
     * @param task Cold-path calculation task
     * @return CompletableFuture that completes when task finishes
     */
    fun <T> submit(task: Callable<T>): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val admissionTask = AdmissionTask(task, future)

        // 🔥 NON-BLOCKING: Try to offer to queue
        val offered = queue.offer(admissionTask)
        queueSize.set(queue.size)

        if (!offered) {
            // 🔥 FAST REJECT: Queue full
            queueFullCounter.increment()
            future.completeExceptionally(
                AdmissionRejectedException("Admission queue full (max=$maxQueueSize)")
            )
            log.warn("[SimpleAdmissionControl] Queue full - rejecting request, queueSize={}", queue.size)
            return future
        }

        return future
    }

    /**
     * 🔥 WORKER LOOP: Blocks here (in worker thread, not HTTP thread)
     */
    private fun workerLoop() {
        while (true) {
            try {
                // 🔥 BLOCKING: Wait for task (only worker thread blocks)
                @Suppress("UNCHECKED_CAST")
                val task = queue.take() as AdmissionTask<*>
                queueSize.set(queue.size)

                // 🔥 SEMAPHORE: Limit in-flight executions
                semaphore.acquire()
                inFlight.incrementAndGet()

                try {
                    @Suppress("UNCHECKED_CAST")
                    val result = task.callable.call() as Any
                    @Suppress("UNCHECKED_CAST")
                    (task.future as CompletableFuture<Any>).complete(result)
                } catch (e: Exception) {
                    task.future.completeExceptionally(e)
                } finally {
                    semaphore.release()
                    inFlight.decrementAndGet()
                }

            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.info("[SimpleAdmissionControl] Worker interrupted")
                break
            } catch (e: Exception) {
                log.error("[SimpleAdmissionControl] Worker error", e)
            }
        }
    }

    /**
     * 🔥 SHUTDOWN: Graceful shutdown
     */
    fun shutdown() {
        log.info("[SimpleAdmissionControl] Shutting down...")
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("[SimpleAdmissionControl] Executor did not terminate in time")
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            log.error("[SimpleAdmissionControl] Shutdown interrupted", e)
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        log.info("[SimpleAdmissionControl] Shutdown complete")
    }

    /**
     * 🔥 DATA CLASS: Admission Task
     */
    data class AdmissionTask<T>(
        val callable: Callable<T>,
        val future: CompletableFuture<T>
    )
}

