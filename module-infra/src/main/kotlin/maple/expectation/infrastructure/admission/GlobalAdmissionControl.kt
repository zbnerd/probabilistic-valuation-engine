package maple.expectation.infrastructure.admission

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import maple.expectation.infrastructure.config.GlobalAdmissionProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue

/**
 * 🔥 PRODUCTION-READY: Global Admission Control with Real Bounded Queue
 *
 * <h3>Purpose</h3>
 * Prevents CPU saturation from unique-key fan-out by limiting concurrent cold misses.
 *
 * <h3>Architecture (FIXED)</h3>
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
 * @param properties Configuration properties
 * @param meterRegistry Micrometer registry
 * @param executor Logic executor for async operations
 */
@Component
class GlobalAdmissionControl(
    private val properties: GlobalAdmissionProperties,
    private val meterRegistry: MeterRegistry,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(GlobalAdmissionControl::class.java)

    // Semaphore limits IN-FLIGHT (executing) requests
    private val semaphore = Semaphore(properties.maxInFlight)
    private val inFlightCount = AtomicInteger(0)

    // 🔥 FIXED: Real bounded queue (not just a counter)
    private val admissionQueue: BlockingQueue<AdmissionRequest<*>> =
        ArrayBlockingQueue(properties.maxQueueSize)

    // Metrics
    private val queueTimeoutCounter: Counter
    private val queueFullCounter: Counter
    private val admissionRejectedCounter: Counter
    private val queueWaitTimeTimer: Timer

    init {
        queueTimeoutCounter = Counter.builder("admission_control.queue.timeout")
            .description("Requests timed out waiting in queue")
            .register(meterRegistry)

        queueFullCounter = Counter.builder("admission_control.queue.full")
            .description("Queue was full when request arrived (FAST REJECT)")
            .register(meterRegistry)

        admissionRejectedCounter = Counter.builder("admission_control.rejected")
            .description("Requests rejected due to queue full")
            .register(meterRegistry)

        // 🔥 ADDED: Queue wait time distribution
        queueWaitTimeTimer = Timer.builder("admission_control.queue_wait_time")
            .description("Time spent waiting in admission queue")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry)

        Gauge.builder("admission_control.in_flight", inFlightCount) { it.get().toDouble() }
            .description("Currently executing cold-path requests")
            .register(meterRegistry)

        Gauge.builder("admission_control.queue_depth", admissionQueue) { it.size.toDouble() }
            .description("Requests waiting in admission queue (REAL BOUNDED QUEUE)")
            .register(meterRegistry)

        // 🔥 FIXED: Start worker pool
        startWorkerPool(properties.workerPoolSize)

        log.info(
            "[AdmissionControl] Initialized: maxInFlight={}, maxQueueSize={}, workerPoolSize={}",
            properties.maxInFlight,
            properties.maxQueueSize,
            properties.workerPoolSize
        )
    }

    /**
     * 🔥 FIXED: Non-blocking submit with real bounded queue
     *
     * Returns immediately (no HTTP thread blocking).
     * If queue is full, returns failed future immediately.
     *
     * @param key Request key (for metrics/logging)
     * @param task Cold-path calculation task
     * @return CompletableFuture with result
     */
    fun <T> submitOrWait(key: String, task: Callable<T>): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val request = AdmissionRequest(key, task, future, System.nanoTime())

        // Fast path: try immediate execution
        if (tryAcquireImmediately(request)) {
            return future
        }

        // 🔥 FIXED: Offer to bounded queue (NON-BLOCKING)
        val offered = admissionQueue.offer(request)

        if (!offered) {
            // 🔥 CRITICAL: Queue full → FAST REJECT (no blocking)
            queueFullCounter.increment()
            admissionRejectedCounter.increment()
            future.completeExceptionally(
                AdmissionRejectedException("Queue full (max=${properties.maxQueueSize})")
            )
            log.warn("[AdmissionControl] Queue full - rejecting request: key={}, queueSize={}", key, admissionQueue.size)
            return future
        }

        return future
    }

    private fun <T> tryAcquireImmediately(request: AdmissionRequest<T>): Boolean {
        if (semaphore.tryAcquire()) {
            inFlightCount.incrementAndGet()
            executeRequest(request)
            return true
        }
        return false
    }

    /**
     * 🔥 FIXED: Worker pool that consumes queue without blocking HTTP threads
     */
    private fun startWorkerPool(size: Int) {
        repeat(size) { workerIndex ->
            executor.executeVoid({
                workerLoop(workerIndex)
            }, TaskContext.of("AdmissionControl", "Worker", "worker-$workerIndex"))
        }
        log.info("[AdmissionControl] Started {} worker threads", size)
    }

    private fun workerLoop(workerIndex: Int) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                // 🔥 FIXED: Block HERE (in worker thread, not HTTP thread)
                @Suppress("UNCHECKED_CAST")
                val request = admissionQueue.take() as AdmissionRequest<*>

                val waitTimeNanos = System.nanoTime() - request.enqueuedAtNanos
                queueWaitTimeTimer.record(waitTimeNanos, TimeUnit.NANOSECONDS)

                // Acquire semaphore (in-flight limit)
                val acquired = semaphore.tryAcquire(properties.queueTimeoutMs, TimeUnit.MILLISECONDS)

                if (!acquired) {
                    // Waited too long in queue
                    queueTimeoutCounter.increment()
                    request.future.completeExceptionally(
                        AdmissionTimeoutException("Queue timeout after ${properties.queueTimeoutMs}ms")
                    )
                    log.debug("[AdmissionControl] Worker {}: Request timed out in queue: key={}", workerIndex, request.key)
                    continue
                }

                inFlightCount.incrementAndGet()

                // Execute request
                try {
                    @Suppress("UNCHECKED_CAST")
                    val result = request.task.call()
                    @Suppress("UNCHECKED_CAST")
                    (request.future as CompletableFuture<Any>).complete(result)
                } catch (e: Exception) {
                    request.future.completeExceptionally(e)
                } finally {
                    semaphore.release()
                    inFlightCount.decrementAndGet()
                }

            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.info("[AdmissionControl] Worker {} interrupted", workerIndex)
                break
            } catch (e: Exception) {
                log.error("[AdmissionControl] Worker {} error", workerIndex, e)
            }
        }
    }

    private fun <T> executeRequest(request: AdmissionRequest<T>) {
        executor.executeVoid({
            try {
                val result = request.task.call()
                request.future.complete(result)
            } catch (e: Exception) {
                request.future.completeExceptionally(e)
            } finally {
                semaphore.release()
                inFlightCount.decrementAndGet()
            }
        }, TaskContext.of("AdmissionControl", "Execute", request.key))
    }

    data class AdmissionRequest<T>(
        val key: String,
        val task: Callable<T>,
        val future: CompletableFuture<T>,
        val enqueuedAtNanos: Long,
    )
}

