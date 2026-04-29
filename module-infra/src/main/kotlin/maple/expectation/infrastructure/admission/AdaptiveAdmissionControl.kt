package maple.expectation.infrastructure.admission

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.lang.management.ManagementFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import maple.expectation.infrastructure.config.GlobalAdmissionProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 🔥 ADVANCED: CPU-based Adaptive Admission Control
 *
 * <h3>Purpose</h3>
 * Dynamically adjusts concurrency limit based on CPU load to prevent system overload.
 *
 * <h3>Adaptive Strategy</h3>
 * <ul>
 *   <li>CPU load > 7.0 → Reduce limit to 50%</li>
 *   <li>CPU load > 5.0 → Reduce limit to 80%</li>
 *   <li>CPU load <= 5.0 → Normal operation (100%)</li>
 * </ul>
 *
 * @param properties Configuration properties
 * @param meterRegistry Micrometer registry
 * @param executor Logic executor for async operations
 */
@Component
@ConditionalOnProperty(name = ["adaptive-admission.enabled"], havingValue = "true", matchIfMissing = false)
class AdaptiveAdmissionControl(
    private val properties: GlobalAdmissionProperties,
    private val meterRegistry: MeterRegistry,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(AdaptiveAdmissionControl::class.java)

    // OS bean for CPU load monitoring
    private val osBean = ManagementFactory.getOperatingSystemMXBean()

    // 🔥 ADAPTIVE: Dynamic semaphore with variable permits
    private val maxPermits: AtomicInteger = AtomicInteger(properties.maxInFlight)
    private val semaphore: Semaphore = Semaphore(properties.maxInFlight)
    private val inFlightCount = AtomicInteger(0)

    // 🔥 REAL BOUNDED QUEUE
    private val admissionQueue: BlockingQueue<AdmissionRequest<*>> =
        ArrayBlockingQueue(properties.maxQueueSize)

    // Metrics
    private val queueTimeoutCounter: Counter
    private val queueFullCounter: Counter
    private val admissionRejectedCounter: Counter
    private val cpuLimitCounter: Counter
    private val queueWaitTimeTimer: Timer
    private val cpuLoadGauge: Gauge

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

        cpuLimitCounter = Counter.builder("admission_control.cpu_limit_reduced")
            .description("CPU limit reduced due to high load")
            .register(meterRegistry)

        queueWaitTimeTimer = Timer.builder("admission_control.queue_wait_time")
            .description("Time spent waiting in admission queue")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry)

        Gauge.builder("admission_control.in_flight", inFlightCount) { it.get().toDouble() }
            .description("Currently executing cold-path requests")
            .register(meterRegistry)

        Gauge.builder("admission_control.queue_depth", admissionQueue) { it.size.toDouble() }
            .description("Requests waiting in admission queue (ADAPTIVE)")
            .register(meterRegistry)

        // 🔥 CPU LOAD GAUGE
        cpuLoadGauge = Gauge.builder("admission_control.cpu_load", osBean) {
            it.systemLoadAverage
        }.description("System CPU load average").register(meterRegistry)

        // 🔥 Start worker pool
        startWorkerPool(properties.workerPoolSize)

        // 🔥 Start CPU monitor (adjust limits every 5 seconds)
        startCpuMonitor()

        log.info(
            "[AdaptiveAdmissionControl] Initialized with CPU-BASED ADAPTIVE CONTROL: maxInFlight={}, maxQueueSize={}, workerPoolSize={}",
            properties.maxInFlight,
            properties.maxQueueSize,
            properties.workerPoolSize,
        )
    }

    /**
     * 🔥 ADVANCED: Submit with CPU-based admission control
     *
     * @param key Request key (for metrics/logging)
     * @param task Cold-path calculation task
     * @return CompletableFuture with result
     */
    fun <T> submitOrWait(key: String, task: Callable<T>): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val request = AdmissionRequest(key, task, future, System.nanoTime())

        // 🔥 CPU-BASED LIMIT CHECK
        if (inFlightCount.get() >= maxPermits.get()) {
            // Queue full AND CPU limit hit
            queueFullCounter.increment()
            admissionRejectedCounter.increment()
            future.completeExceptionally(
                AdmissionRejectedException("CPU limit reached (max=${maxPermits.get()})"),
            )
            log.warn(
                "[AdaptiveAdmissionControl] CPU limit reached - rejecting: key={}, inFlight={}, maxPermits={}",
                key,
                inFlightCount.get(),
                maxPermits.get(),
            )
            return future
        }

        // Fast path: try immediate execution
        if (tryAcquireImmediately(request)) {
            return future
        }

        // 🔥 Offer to bounded queue (NON-BLOCKING)
        val offered = admissionQueue.offer(request)

        if (!offered) {
            // 🔥 CRITICAL: Queue full → FAST REJECT (no blocking)
            queueFullCounter.increment()
            admissionRejectedCounter.increment()
            future.completeExceptionally(
                AdmissionRejectedException("Queue full (max=${properties.maxQueueSize})"),
            )
            log.warn("[AdaptiveAdmissionControl] Queue full - rejecting request: key={}, queueSize={}", key, admissionQueue.size)
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
     * 🔥 CPU MONITOR: Adjust admission limits based on CPU load
     */
    private fun startCpuMonitor() {
        val cpuMonitorExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread.ofVirtual().name("adaptive-admission-cpu-monitor").unstarted(runnable)
        }

        cpuMonitorExecutor.scheduleAtFixedRate(
            {
                try {
                    adjustLimitsBasedOnCpu()
                } catch (e: Exception) {
                    log.error("[AdaptiveAdmissionControl] CPU monitor error", e)
                }
            },
            5, // Initial delay
            5, // Check every 5 seconds
            TimeUnit.SECONDS,
        )

        log.info("[AdaptiveAdmissionControl] CPU monitor started")
    }

    /**
     * 🔥 ADAPTIVE: Adjust limits based on CPU load
     */
    private fun adjustLimitsBasedOnCpu() {
        val cpuLoad = osBean.systemLoadAverage
        val currentMax = maxPermits.get()
        val newMax = calculateDynamicLimit(cpuLoad, properties.maxInFlight)

        if (newMax != currentMax) {
            maxPermits.set(newMax)

            // 🔥 Adjust semaphore permits
            val currentPermits = semaphore.availablePermits()
            val permitDiff = newMax - (currentMax - currentPermits)

            if (permitDiff > 0) {
                // Increase permits
                semaphore.release(permitDiff)
                log.info(
                    "[AdaptiveAdmissionControl] ✅ Increased permits: {} → {} (CPU load: {})",
                    currentMax,
                    newMax,
                    String.format("%.2f", cpuLoad),
                )
            } else if (permitDiff < 0) {
                // Decrease permits (wait for current permits to be released)
                log.warn(
                    "[AdaptiveAdmissionControl] 🔴 Decreasing permits: {} → {} (CPU load: {})",
                    currentMax,
                    newMax,
                    String.format("%.2f", cpuLoad),
                )
                cpuLimitCounter.increment()
            }
        }

        log.debug(
            "[AdaptiveAdmissionControl] CPU load: {}, maxPermits: {}, inFlight: {}, queueSize: {}",
            String.format("%.2f", cpuLoad),
            maxPermits.get(),
            inFlightCount.get(),
            admissionQueue.size,
        )
    }

    /**
     * 🔥 Calculate dynamic limit based on CPU load
     *
     * @param cpuLoad System load average
     * @param baseLimit Base limit from configuration
     * @return Dynamic limit
     */
    private fun calculateDynamicLimit(cpuLoad: Double, baseLimit: Int): Int = when {
        cpuLoad > 7.0 -> {
            // 🔴 CRITICAL: Reduce to 50%
            log.warn("[AdaptiveAdmissionControl] 🔴 CPU load CRITICAL: {}", String.format("%.2f", cpuLoad))
            (baseLimit * 0.5).toInt()
        }
        cpuLoad > 5.0 -> {
            // 🟡 WARNING: Reduce to 80%
            log.warn("[AdaptiveAdmissionControl] 🟡 CPU load HIGH: {}", String.format("%.2f", cpuLoad))
            (baseLimit * 0.8).toInt()
        }
        else -> {
            // 🟢 NORMAL: Full capacity
            baseLimit
        }
    }

    /**
     * 🔥 FIXED: Worker pool that consumes queue without blocking HTTP threads
     */
    private fun startWorkerPool(size: Int) {
        repeat(size) { workerIndex ->
            executor.executeVoid({
                workerLoop(workerIndex)
            }, TaskContext.of("AdaptiveAdmissionControl", "Worker", "worker-$workerIndex"))
        }
        log.info("[AdaptiveAdmissionControl] Started {} worker threads", size)
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
                        AdmissionTimeoutException("Queue timeout after ${properties.queueTimeoutMs}ms"),
                    )
                    log.debug("[AdaptiveAdmissionControl] Worker {}: Request timed out in queue: key={}", workerIndex, request.key)
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
                log.info("[AdaptiveAdmissionControl] Worker {} interrupted", workerIndex)
                break
            } catch (e: Exception) {
                log.error("[AdaptiveAdmissionControl] Worker {} error", workerIndex, e)
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
        }, TaskContext.of("AdaptiveAdmissionControl", "Execute", request.key))
    }

    data class AdmissionRequest<T>(
        val key: String,
        val task: Callable<T>,
        val future: CompletableFuture<T>,
        val enqueuedAtNanos: Long,
    )
}
