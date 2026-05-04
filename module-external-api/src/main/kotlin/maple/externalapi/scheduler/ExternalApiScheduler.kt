package maple.externalapi.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
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
import maple.externalapi.port.out.ExternalApiArtifactStorePort
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
    private val artifactStore: ExternalApiArtifactStorePort,
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.rate-limit.ocid-lookup-permits-per-second:400}")
    private val ocidLookupPermitsPerSecond: Int,
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
            log.info("[Scheduler] run-on-startup enabled, triggering full pipeline")
            triggerFullPipeline()
        }
    }

    @Scheduled(cron = "\${external-api.schedule.ocid-lookup-cron:0 0 3 * * *}")
    fun scheduledPipeline() {
        triggerFullPipeline()
    }

    fun triggerFullPipeline() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[Scheduler] pipeline already running, skipping")
            return
        }
        try {
            val existingOcids = artifactStore.listStoredKeys(ExternalApiEndpoint.OCID_LOOKUP)
            if (existingOcids.isEmpty()) {
                doOcidLookup()
            } else {
                log.info("[Scheduler] OCID lookup already done ({} files), skipping", existingOcids.size)
            }
            doCharacterBasicLookup()
        } finally {
            running.set(false)
        }
    }

    private fun doOcidLookup() {
        val rateLimiter = newRateLimiter(ocidLookupPermitsPerSecond)

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
            val permits = acquirePermits(rateLimiter, igns.size - processed)
            if (permits == 0) continue

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
                logProgress("OCID lookup", progress, igns.size, storedCount.get(), failCount.get(), start)
            }
        }

        logSummary("OCID lookup", igns.size, successCount.get(), storedCount.get(), failCount.get(), start)
    }

    private fun doCharacterBasicLookup() {
        val rateLimiter = newRateLimiter()

        val ocidKeys = artifactStore.listStoredKeys(ExternalApiEndpoint.OCID_LOOKUP)
        if (ocidKeys.isEmpty()) {
            log.warn("[Scheduler] no stored OCIDs found, skipping CHARACTER_BASIC")
            return
        }

        val ocidMap = readStoredOcids(ocidKeys)
        if (ocidMap.isEmpty()) {
            log.warn("[Scheduler] failed to parse any OCIDs, skipping CHARACTER_BASIC")
            return
        }

        log.info("[Scheduler] ========== CHARACTER_BASIC lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, threads=virtual",
            ocidMap.size,
            permitsPerSecond,
            batchSize,
        )

        val start = Instant.now()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val storedCount = AtomicInteger(0)
        var processed = 0
        var lastProgressLog = 0
        val entries = ocidMap.entries.toList()

        while (processed < entries.size) {
            val permits = acquirePermits(rateLimiter, entries.size - processed)
            if (permits == 0) continue

            val chunk = entries.subList(processed, processed + permits)
            processed += permits

            val futures = chunk.map { (ign, ocid) ->
                executor.submit(
                    Callable {
                        try {
                            val result = fetchUseCase.fetchSingle(
                                provider = ExternalApiProvider.NEXON,
                                endpoint = ExternalApiEndpoint.CHARACTER_BASIC,
                                requestKey = ocid,
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
                logProgress("CHARACTER_BASIC", progress, entries.size, storedCount.get(), failCount.get(), start)
            }
        }

        logSummary("CHARACTER_BASIC", entries.size, successCount.get(), storedCount.get(), failCount.get(), start)
    }

    private fun readStoredOcids(keys: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (key in keys) {
            try {
                val bytes = artifactStore.read(ExternalApiEndpoint.OCID_LOOKUP, key)
                if (bytes != null) {
                    val node = objectMapper.readTree(bytes)
                    val ocid = node.get("ocid")?.asText()
                    if (ocid != null) {
                        result[key] = ocid
                    }
                }
            } catch (ex: Exception) {
                log.debug("[Scheduler] failed to parse OCID for key={}", key)
            }
        }
        return result
    }

    private fun newRateLimiter(permits: Int = permitsPerSecond): Bucket = Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(permits.toLong())
                .refillIntervally(permits.toLong(), Duration.ofSeconds(1))
                .build(),
        )
        .build()

    private fun acquirePermits(rateLimiter: Bucket, remaining: Int): Int {
        val maxBatch = minOf(batchSize, remaining)
        return rateLimiter.tryConsumeAsMuchAsPossible(maxBatch.toLong()).toInt().also {
            if (it == 0) Thread.sleep(Duration.ofMillis(100))
        }
    }

    private fun logProgress(phase: String, progress: Int, total: Int, stored: Int, fails: Int, start: Instant) {
        val elapsedSec = Duration.between(start, Instant.now()).toMillis() / 1000.0
        val rate = if (elapsedSec > 0) "%.0f".format(progress / elapsedSec) else "?"
        val storedRate = if (elapsedSec > 0) "%.0f".format(stored / elapsedSec) else "?"
        log.info(
            "[Scheduler] {}: {}/{} (stored={} @{}files/s, fail={}, rate={}files/s, elapsed={}s)",
            phase,
            progress,
            total,
            stored,
            storedRate,
            fails,
            rate,
            elapsedSec.toLong(),
        )
    }

    private fun logSummary(phase: String, total: Int, success: Int, stored: Int, fails: Int, start: Instant) {
        val elapsedSec = Duration.between(start, Instant.now()).toMillis() / 1000.0
        val rate = if (elapsedSec > 0) "%.0f".format(total / elapsedSec) else "?"
        val storedRate = if (elapsedSec > 0) "%.0f".format(stored / elapsedSec) else "?"
        log.info("[Scheduler] ========== {} complete ==========", phase)
        log.info(
            "[Scheduler] result: total={}, stored={} @{}files/s, success={}, fail={}, elapsed={}s, avgRate={}files/s",
            total,
            stored,
            storedRate,
            success,
            fails,
            elapsedSec.toLong(),
            rate,
        )
    }
}
