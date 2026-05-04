package maple.externalapi.scheduler

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import java.time.Duration
import java.time.Instant
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

        log.info(
            "[Scheduler] ========== OCID lookup start ==========",
        )
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, store={}",
            igns.size,
            permitsPerSecond,
            batchSize,
            storeBasePath,
        )

        val start = Instant.now()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val storedCount = AtomicInteger(0)

        igns.chunked(batchSize).forEach { chunk ->
            chunk.forEach { ign ->
                while (!rateLimiter.tryConsume(1)) {
                    Thread.sleep(5)
                }
                val result = fetchUseCase.fetchSingle(
                    provider = ExternalApiProvider.NEXON,
                    endpoint = ExternalApiEndpoint.OCID_LOOKUP,
                    requestKey = ign,
                    characterName = ign,
                )
                if (result.success) {
                    successCount.incrementAndGet()
                    if (result.payloadRef != null) {
                        storedCount.incrementAndGet()
                    }
                } else {
                    failCount.incrementAndGet()
                }
            }
            val progress = successCount.get() + failCount.get()
            if (progress % 10000 == 0) {
                val elapsed = Duration.between(start, Instant.now())
                val rate = if (elapsed.seconds > 0) progress / elapsed.seconds else 0
                log.info(
                    "[Scheduler] progress: {}/{} (stored={}, fail={}, rate={}/s, elapsed={}s)",
                    progress,
                    igns.size,
                    storedCount.get(),
                    failCount.get(),
                    rate,
                    elapsed.seconds,
                )
            }
        }

        val elapsed = Duration.between(start, Instant.now())
        val totalProcessed = successCount.get() + failCount.get()
        val finalRate = if (elapsed.seconds > 0) totalProcessed / elapsed.seconds else 0
        log.info(
            "[Scheduler] ========== OCID lookup complete ==========",
        )
        log.info(
            "[Scheduler] result: total={}, stored={}, success={}, fail={}, elapsed={}s, avgRate={}/s",
            igns.size,
            storedCount.get(),
            successCount.get(),
            failCount.get(),
            elapsed.seconds,
            finalRate,
        )
    }
}
