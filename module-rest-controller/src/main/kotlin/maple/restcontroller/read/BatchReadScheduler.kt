package maple.restcontroller.read

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled

class BatchReadScheduler(
    private val buffer: LocalRequestBuffer,
    private val registry: InflightRequestRegistry,
    private val resolver: BatchResolver,
    private val metrics: V6ReadMetrics,
    private val properties: V6ReadProperties,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var running = false

    override fun start() {
        running = true
        log.info("BatchReadScheduler started")
    }

    override fun stop() {
        stop { }
    }

    override fun stop(callback: Runnable) {
        running = false
        log.info("BatchReadScheduler stopping — draining remaining requests")

        buffer.stopAccepting()

        val deadlineNanos = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(properties.shutdownDrainTimeoutSeconds)

        var drained = 0
        val pendingFutures = mutableListOf<CompletableFuture<*>>()
        while (!buffer.isEmpty() && System.nanoTime() < deadlineNanos) {
            val batch = buffer.drain(properties.maxBatchSize)
            val future = resolver.resolveBatch(batch)
            pendingFutures += future
            future.whenComplete { result, ex ->
                if (ex != null) {
                    log.error("Shutdown drain resolveBatch failed: batchSize={}", batch.size, ex)
                    return@whenComplete
                }
                applyToDeferreds(result, batch)
            }
            drained += batch.size
        }

        // Wait for in-flight resolveBatch to complete so deferreds get 200/404/503.
        // SmartLifecycle.stop(callback) contract: callback is invoked when stop is done.
        // We use the composite future's own whenComplete to drive the callback — no `.join()`.
        val onShutdownComplete = Runnable {
            // Resolve any deferreds the in-flight futures didn't get to (timeout, failure)
            registry.failAll(serviceUnavailable())
            val remaining = buffer.size()
            if (remaining > 0) {
                log.warn("Shutdown deadline reached — failing {} pending requests", remaining)
                buffer.failAllPending()
                registry.failAll(serviceUnavailable())
            }
            log.info("BatchReadScheduler stopped — drained={}, remaining={}", drained, remaining)
            callback.run()
        }

        if (pendingFutures.isEmpty()) {
            onShutdownComplete.run()
        } else {
            CompletableFuture.allOf(*pendingFutures.toTypedArray())
                .orTimeout(properties.shutdownDrainTimeoutSeconds, TimeUnit.SECONDS)
                .whenComplete { _, ex ->
                    if (ex != null) {
                        log.warn("Some drain futures did not complete: {}", ex.message)
                    }
                    onShutdownComplete.run()
                }
        }
    }

    private fun serviceUnavailable(): ResponseEntity<Any> = ResponseEntity.status(503)
        .header("Retry-After", "1")
        .build()

    override fun isRunning(): Boolean = running

    /**
     * Phase ordinal for the batch-read scheduler. Sized to 100 less than [Integer.MAX_VALUE]
     * so it runs after all business schedulers but before any future "very last" hook.
     */
    override fun getPhase(): Int = PHASE_BATCH_READ

    private companion object {
        private const val PHASE_BATCH_READ: Int = Integer.MAX_VALUE - 100
    }

    override fun isAutoStartup(): Boolean = true

    @Scheduled(fixedDelayString = "\${expectation.v6.batch-window-ms:10}")
    fun scheduledDrain() {
        if (!running) return
        val batch = buffer.drain(properties.maxBatchSize)
        if (batch.isEmpty()) return

        val sample = io.micrometer.core.instrument.Timer.start()
        resolver.resolveBatch(batch).whenComplete { result, ex ->
            sample.stop(metrics.batchLatency)
            if (ex != null) {
                log.error("scheduledDrain resolveBatch failed: batchSize={}", batch.size, ex)
                return@whenComplete
            }
            applyToDeferreds(result, batch)
        }
    }

    /**
     * Apply a [BatchResolveResult] to its matching deferreds. Resolved items get
     * the HTTP response from [ExpectationReadResponseMapper]; pending items are
     * left alone so the deferred's timeout (wired by the facade) fires later.
     */
    private fun applyToDeferreds(result: BatchResolveResult, batch: List<ReadRequest>) {
        val itemByKey = result.resolved.associateBy { it.userIgn to it.presetNo }
        batch.forEach { request ->
            val item = itemByKey[request.userIgn to request.presetNo] ?: return@forEach
            val deferreds = registry.getAndRemove(request.userIgn, request.presetNo)
            if (deferreds.isEmpty()) return@forEach
            val response = ExpectationReadResponseMapper.toResponse(item)
            deferreds.forEach { deferred -> deferred.setResult(response) }
        }
    }
}
