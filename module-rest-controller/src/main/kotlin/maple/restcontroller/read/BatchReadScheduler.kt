package maple.restcontroller.read

import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.TimeUnit

class BatchReadScheduler(
    private val buffer: LocalRequestBuffer,
    private val registry: InflightRequestRegistry,
    private val resolver: BatchResolver,
    private val metrics: V6ReadMetrics,
    private val properties: V6ReadProperties
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
        var failed = 0
        while (!buffer.isEmpty() && System.nanoTime() < deadlineNanos) {
            val batch = buffer.drain(properties.maxBatchSize)
            val result = resolver.resolveBatch(batch)
            applyToDeferreds(result, batch)
            drained += result.resolvedCount
            failed += batch.size - result.resolvedCount
        }

        // Resolve any drained-but-unhandled deferreds with 503
        val serviceUnavailable = ResponseEntity.status(503)
            .header("Retry-After", "1")
            .build<Any>()
        registry.failAll(serviceUnavailable)

        val remaining = buffer.size()
        if (remaining > 0) {
            log.warn("Shutdown deadline reached — failing {} pending requests", remaining)
            buffer.failAllPending()
            registry.failAll(
                ResponseEntity.status(503)
                    .header("Retry-After", "1")
                    .build<Any>()
            )
        }

        log.info("BatchReadScheduler stopped — resolved={}, failed={}, remaining={}", drained, failed, remaining)
        callback.run()
    }

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
        val result = resolver.resolveBatch(batch)
        applyToDeferreds(result, batch)
        sample.stop(metrics.batchLatency)
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
