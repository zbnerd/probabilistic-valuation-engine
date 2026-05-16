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
    private val queryService: ReadModelQueryService,
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
            val resolved = resolveBatch(batch)
            drained += resolved
            failed += batch.size - resolved
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

    private fun resolveBatch(batch: List<ReadRequest>): Int {
        if (batch.isEmpty()) return 0

        val requests = batch.associate { it.userIgn to it.presetNo }
        val results = queryService.batchQuery(requests)
        var resolved = 0

        batch.forEach { request ->
            val deferreds = registry.getAndRemove(request.userIgn)
            val response = results[request.userIgn]

            if (response != null) {
                metrics.recordHit()
                deferreds.forEach { it.setResult(ResponseEntity.ok(response)) }
                resolved++
            } else {
                metrics.recordMiss("read_model_empty")
            }
        }
        return resolved
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Integer.MAX_VALUE - 100

    override fun isAutoStartup(): Boolean = true

    @Scheduled(fixedDelayString = "\${expectation.v6.batch-window-ms:10}")
    fun scheduledDrain() {
        if (!running) return
        val batch = buffer.drain(properties.maxBatchSize)
        if (batch.isEmpty()) return

        val sample = io.micrometer.core.instrument.Timer.start()
        resolveBatch(batch)
        sample.stop(metrics.batchLatency)
    }
}
