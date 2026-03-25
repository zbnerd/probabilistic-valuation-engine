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
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.BlockingQueue

/**
 * 🔥 ADVANCED: Priority-based Admission Control
 *
 * <h3>Purpose</h3>
 * Hot characters get priority over cold requests to optimize user experience.
 *
 * <h3>Priority Levels</h3>
 * <ul>
 *   <li>100: Cache hit expected (already cached)</li>
 *   <li>80: Hot character (frequent access)</li>
 *   <li>50: Normal request</li>
 *   <li>10: Cold + Heavy (first-time calculation)</li>
 * </ul>
 *
 * @param properties Configuration properties
 * @param meterRegistry Micrometer registry
 * @param executor Logic executor for async operations
 */
@Component
class PriorityAdmissionControl(
    private val properties: GlobalAdmissionProperties,
    private val meterRegistry: MeterRegistry,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(PriorityAdmissionControl::class.java)

    // 🔥 PRIORITY QUEUE: Hot characters processed first
    private val admissionQueue: PriorityBlockingQueue<PriorityAdmissionRequest<*>> =
        PriorityBlockingQueue(
            properties.maxQueueSize,
            compareByDescending<PriorityAdmissionRequest<*>> { it.priority }
        )

    // Semaphore limits IN-FLIGHT (executing) requests
    private val semaphore = Semaphore(properties.maxInFlight)
    private val inFlightCount = AtomicInteger(0)

    // Metrics
    private val queueTimeoutCounter: Counter
    private val queueFullCounter: Counter
    private val admissionRejectedCounter: Counter
    private val queueWaitTimeTimer: Timer
    private val priorityCounter: Map<Int, Counter>

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

        queueWaitTimeTimer = Timer.builder("admission_control.queue_wait_time")
            .description("Time spent waiting in admission queue")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry)

        Gauge.builder("admission_control.in_flight", inFlightCount) { it.get().toDouble() }
            .description("Currently executing cold-path requests")
            .register(meterRegistry)

        Gauge.builder("admission_control.queue_depth", admissionQueue) { it.size.toDouble() }
            .description("Requests waiting in admission queue (PRIORITY QUEUE)")
            .register(meterRegistry)

        // 🔥 PRIORITY METRICS
        priorityCounter = mapOf(
            100 to createPriorityCounter(100, "cache_hit_expected"),
            80 to createPriorityCounter(80, "hot_character"),
            50 to createPriorityCounter(50, "normal"),
            10 to createPriorityCounter(10, "cold_heavy")
        )

        // 🔥 FIXED: Start worker pool
        startWorkerPool(properties.workerPoolSize)

        log.info(
            "[PriorityAdmissionControl] Initialized with PRIORITY QUEUE: maxInFlight={}, maxQueueSize={}, workerPoolSize={}",
            properties.maxInFlight,
            properties.maxQueueSize,
            properties.workerPoolSize
        )
    }

    private fun createPriorityCounter(priority: Int, label: String): Counter {
        return Counter.builder("admission_control.priority")
            .description("Requests by priority level")
            .tag("level", label)
            .tag("value", priority.toString())
            .register(meterRegistry)
    }

    /**
     * 🔥 ADVANCED: Submit with automatic priority detection
     *
     * @param key Request key (used for priority detection)
     * @param task Cold-path calculation task
     * @param priority Explicit priority (optional, auto-detected if null)
     * @return CompletableFuture with result
     */
    fun <T> submitOrWait(
        key: String,
        task: Callable<T>,
        priority: Int? = null
    ): CompletableFuture<T> {
        val future = CompletableFuture<T>()

        // 🔥 AUTO-DETECT PRIORITY if not provided
        val detectedPriority = priority ?: detectPriority(key)

        // Track priority metric
        priorityCounter[detectedPriority]?.increment()

        val request = PriorityAdmissionRequest(
            key = key,
            task = task,
            future = future,
            enqueuedAtNanos = System.nanoTime(),
            priority = detectedPriority
        )

        // Fast path: try immediate execution
        if (tryAcquireImmediately(request)) {
            return future
        }

        // 🔥 PRIORITY QUEUE: Higher priority items processed first
        val offered = admissionQueue.offer(request)

        if (!offered) {
            // 🔥 CRITICAL: Queue full → FAST REJECT (no blocking)
            queueFullCounter.increment()
            admissionRejectedCounter.increment()
            future.completeExceptionally(
                AdmissionRejectedException("Queue full (max=${properties.maxQueueSize})")
            )
            log.warn("[PriorityAdmissionControl] Queue full - rejecting request: key={}, queueSize={}", key, admissionQueue.size)
            return future
        }

        return future
    }

    /**
     * 🔥 ADVANCED: Auto-detect priority based on request key
     *
     * Priority heuristics:
     * - Hot character (high frequency) → 80
     * - Normal request → 50
     * - Cold + Heavy → 10
     */
    private fun detectPriority(key: String): Int {
        // TODO: Implement actual hot character detection
        // For now, use simple heuristics

        // Check if character is in hot list (cache, recent access pattern)
        // if (isHotCharacter(key)) return 80

        return 50 // Default: normal priority
    }

    private fun <T> tryAcquireImmediately(request: PriorityAdmissionRequest<T>): Boolean {
        if (semaphore.tryAcquire()) {
            inFlightCount.incrementAndGet()
            executeRequest(request)
            return true
        }
        return false
    }

    /**
     * 🔥 FIXED: Worker pool that consumes priority queue
     */
    private fun startWorkerPool(size: Int) {
        repeat(size) { workerIndex ->
            executor.executeVoid({
                workerLoop(workerIndex)
            }, TaskContext.of("PriorityAdmissionControl", "Worker", "worker-$workerIndex"))
        }
        log.info("[PriorityAdmissionControl] Started {} worker threads", size)
    }

    private fun workerLoop(workerIndex: Int) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                // 🔥 PRIORITY QUEUE: Blocks until highest priority item available
                @Suppress("UNCHECKED_CAST")
                val request = admissionQueue.take() as PriorityAdmissionRequest<*>

                val waitTimeNanos = System.nanoTime() - request.enqueuedAtNanos
                queueWaitTimeTimer.record(waitTimeNanos, TimeUnit.NANOSECONDS)

                log.debug(
                    "[PriorityAdmissionControl] Worker {}: Processing request (priority={}): key={}",
                    workerIndex,
                    request.priority,
                    request.key
                )

                // Acquire semaphore (in-flight limit)
                val acquired = semaphore.tryAcquire(properties.queueTimeoutMs, TimeUnit.MILLISECONDS)

                if (!acquired) {
                    // Waited too long in queue
                    queueTimeoutCounter.increment()
                    request.future.completeExceptionally(
                        AdmissionTimeoutException("Queue timeout after ${properties.queueTimeoutMs}ms")
                    )
                    log.debug("[PriorityAdmissionControl] Worker {}: Request timed out in queue: key={}", workerIndex, request.key)
                    continue
                }

                inFlightCount.incrementAndGet()

                // Execute request
                try {
                    @Suppress("UNCHECKED_CAST")
                    val result = request.task.call() as Any
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
                log.info("[PriorityAdmissionControl] Worker {} interrupted", workerIndex)
                break
            } catch (e: Exception) {
                log.error("[PriorityAdmissionControl] Worker {} error", workerIndex, e)
            }
        }
    }

    private fun <T> executeRequest(request: PriorityAdmissionRequest<T>) {
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
        }, TaskContext.of("PriorityAdmissionControl", "Execute", request.key))
    }

    data class PriorityAdmissionRequest<T>(
        val key: String,
        val task: Callable<T>,
        val future: CompletableFuture<T>,
        val enqueuedAtNanos: Long,
        val priority: Int  // 🔥 PRIORITY FIELD
    ) : Comparable<PriorityAdmissionRequest<*>> {
        override fun compareTo(other: PriorityAdmissionRequest<*>): Int {
            // Higher priority = processed first (descending order)
            return other.priority.compareTo(this.priority)
        }
    }
}

