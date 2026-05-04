package maple.externalapi.scheduler

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.inbound.FetchExternalApiUseCase
import maple.externalapi.reader.UserIgnCsvReader
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class ExternalApiScheduler(
    private val fetchUseCase: FetchExternalApiUseCase,
    private val csvReader: UserIgnCsvReader,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:/data/external-api}")
    private val storeBasePath: String,
    @Value("\${external-api.schedule.run-on-startup:false}")
    private val runOnStartup: Boolean,
) {
    private val log = LoggerFactory.getLogger(ExternalApiScheduler::class.java)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        if (runOnStartup) {
            log.info("[Scheduler] run-on-startup enabled, triggering OCID lookup")
            triggerOcidLookup()
        }
    }

    @Scheduled(cron = "\${external-api.schedule.ocid-lookup-cron:0 0 3 * * *}")
    fun scheduledOcidLookup() {
        triggerOcidLookup()
    }

    fun triggerOcidLookup() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[Scheduler] OCID lookup already running, skipping")
            return
        }
        try {
            doOcidLookup()
        } finally {
            running.set(false)
        }
    }

    private fun doOcidLookup() {
        val rateLimiter = Bucket.builder()
            .addLimit(
                Bandwidth.builder()
                    .capacity(permitsPerSecond.toLong())
                    .refillIntervally(permitsPerSecond.toLong(), Duration.ofSeconds(1))
                    .build(),
            )
            .build()

        val igns = csvReader.readAll()
        if (igns.isEmpty()) {
            log.warn("[Scheduler] no IGNs to process")
            return
        }

        log.info("[Scheduler] ========== OCID lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, store={}, threads=virtual",
            igns.size,
            permitsPerSecond,
            batchSize,
            storeBasePath,
        )

        val start = Instant.now()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val storedCount = AtomicInteger(0)
        var processed = 0
        var lastProgressLog = 0

        while (processed < igns.size) {
            val remaining = igns.size - processed
            val maxBatch = minOf(batchSize, remaining)
            val permits = rateLimiter.tryConsumeAsMuchAsPossible(maxBatch.toLong()).toInt()

            if (permits == 0) {
                Thread.sleep(Duration.ofMillis(100))
                continue
            }

            val chunk = igns.subList(processed, processed + permits)
            processed += permits

            val futures = chunk.map { ign ->
                executor.submit(
                    Callable {
                        try {
                            val result = fetchUseCase.fetchSingle(
                                provider = ExternalApiProvider.NEXON,
                                endpoint = ExternalApiEndpoint.OCID_LOOKUP,
                                requestKey = ign,
                                characterName = ign,
                            )
                            if (result.success) {
                                successCount.incrementAndGet()
                                if (result.payloadRef != null) storedCount.incrementAndGet()
                            } else {
                                failCount.incrementAndGet()
                            }
                        } catch (ex: Exception) {
                            failCount.incrementAndGet()
                        }
                    },
                )
            }

            futures.forEach { it.get() }

            val progress = successCount.get() + failCount.get()
            if (progress - lastProgressLog >= 5000) {
                lastProgressLog = progress
                logProgress(progress, igns.size, storedCount.get(), failCount.get(), start)
            }
        }

        val elapsed = Duration.between(start, Instant.now())
        val elapsedSec = elapsed.toMillis() / 1000.0
        val totalProcessed = successCount.get() + failCount.get()
        val finalRate = if (elapsedSec > 0) "%.0f".format(totalProcessed / elapsedSec) else "?"
        val storedRate = if (elapsedSec > 0) "%.0f".format(storedCount.get() / elapsedSec) else "?"
        log.info("[Scheduler] ========== OCID lookup complete ==========")
        log.info(
            "[Scheduler] result: total={}, stored={} @{}files/s, success={}, fail={}, elapsed={}s, avgRate={}files/s",
            igns.size,
            storedCount.get(),
            storedRate,
            successCount.get(),
            failCount.get(),
            elapsed.seconds,
            finalRate,
        )
    }

    private fun logProgress(progress: Int, total: Int, stored: Int, fails: Int, start: Instant) {
        val elapsed = Duration.between(start, Instant.now())
        val elapsedSec = elapsed.toMillis() / 1000.0
        val rate = if (elapsedSec > 0) "%.0f".format(progress / elapsedSec) else "?"
        val storedRate = if (elapsedSec > 0) "%.0f".format(stored / elapsedSec) else "?"
        log.info(
            "[Scheduler] progress: {}/{} (stored={} @{}files/s, fail={}, totalRate={}files/s, elapsed={}s)",
            progress,
            total,
            stored,
            storedRate,
            fails,
            rate,
            elapsed.seconds,
        )
    }
}
