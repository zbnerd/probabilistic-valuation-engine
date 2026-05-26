package maple.restcontroller.read

import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.urgent.UrgentCharacterRequest
import maple.restcontroller.urgent.UrgentTriggerPublisher
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.util.concurrent.TimeUnit

class BatchReadScheduler(
    private val buffer: LocalRequestBuffer,
    private val registry: InflightRequestRegistry,
    private val queryService: ReadModelQueryService,
    private val cacheService: ReadModelCacheService,
    private val urgentPublisher: UrgentTriggerPublisher?,
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
        var resolved = 0

        // 1. Redis cache lookup — split hits / misses
        val (cacheHits, cacheMisses) = cacheService.multiGet(requests)

        // 2. Resolve cache hits directly
        cacheHits.forEach { (userIgn, response) ->
            val deferreds = registry.getAndRemove(userIgn, response.presetNo)
            metrics.recordHit()
            metrics.recordRedisHit()
            deferreds.forEach { it.setResult(ResponseEntity.ok(response)) }
            resolved++
        }

        // 3. DB batch query for cache misses, including urgent-pending keys.
        if (cacheMisses.isNotEmpty()) {
            val dbResults = queryService.batchQuery(
                cacheMisses,
                Duration.ofSeconds(properties.readModelFreshnessSeconds),
            )

            // 4. Write DB results to Redis cache
            cacheService.multiPut(dbResults)

            // 5. Resolve miss deferreds
            cacheMisses.keys.forEach { userIgn ->
                val presetNo = cacheMisses[userIgn] ?: 1
                val deferreds = registry.getAndRemove(userIgn, presetNo)
                val response = dbResults[userIgn]

                if (response != null) {
                    metrics.recordHit()
                    metrics.recordDbHit()
                    deferreds.forEach { it.setResult(ResponseEntity.ok(response)) }
                    resolved++
                } else {
                    metrics.recordMiss("read_model_empty")

                    // Check negative cache first (character previously confirmed not found by Nexon)
                    if (cacheService.getNegativeCache(userIgn)) {
                        val notFoundResponse = ResponseEntity.status(404)
                            .header("X-Error-Reason", "character-not-found")
                            .build<Any>()
                        deferreds.forEach { it.setResult(notFoundResponse) }
                        resolved++
                        return@forEach
                    }

                    // Trigger urgent pipeline (with dedup via Redis SETNX)
                    if (urgentPublisher != null && cacheService.tryMarkUrgentPending(userIgn)) {
                        urgentPublisher.publish(UrgentCharacterRequest(userIgn = userIgn, presetNo = presetNo))
                        metrics.urgentTriggerTotal.increment()
                        log.info("Triggered urgent pipeline: userIgn={}", maskIgn(userIgn))
                    }

                    // Otherwise DeferredResult will time out → 202 Accepted
                }
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
