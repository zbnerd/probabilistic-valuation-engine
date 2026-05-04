package maple.externalapi.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import jakarta.annotation.PreDestroy
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.reader.UserIgnCsvReader
import maple.externalapi.snapshot.ChunkedSnapshotSink
import maple.externalapi.snapshot.SnapshotChunkRecord
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.port.inbound.FetchExternalApiUseCase
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class ExternalApiScheduler(
    private val fetchUseCase: FetchExternalApiUseCase,
    private val clientPort: ExternalApiClientPort,
    private val csvReader: UserIgnCsvReader,
    private val artifactStore: ExternalApiArtifactStorePort,
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val eventPublisher: SnapshotChunkEventPublisher,
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
    private val shutdown = AtomicBoolean(false)
    private val ocidCache = AtomicReference<Map<String, String>>(emptyMap())
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        loadOcidCache()
        executor.submit { runItemEquipmentLoop() }
        if (runOnStartup) {
            log.info("[Scheduler] run-on-startup enabled, triggering daily refresh")
            triggerDailyRefresh()
        }
    }

    @Scheduled(cron = "\${external-api.schedule.daily-cron:0 0 3 * * *}")
    fun scheduledDailyRefresh() {
        triggerDailyRefresh()
    }

    fun triggerDailyRefresh() {
        if (!acquireLock(120_000)) {
            log.warn("[Scheduler] could not acquire lock for daily refresh, skipping")
            return
        }
        try {
            doOcidLookup()
            loadOcidCache()
            doCharacterBasicLookup()
        } finally {
            running.set(false)
        }
    }

    private fun runItemEquipmentLoop() {
        log.info("[Scheduler] ITEM_EQUIPMENT continuous loop started")
        while (!shutdown.get()) {
            val entries = ocidCache.get().entries.toList()
            if (entries.isEmpty()) {
                log.warn("[Scheduler] OCID cache empty, waiting 30s")
                Thread.sleep(Duration.ofSeconds(30))
                loadOcidCache()
                continue
            }
            if (!acquireLock(120_000)) {
                Thread.sleep(Duration.ofSeconds(5))
                continue
            }
            try {
                doItemEquipmentLookup(entries)
            } finally {
                running.set(false)
            }
        }
        log.info("[Scheduler] ITEM_EQUIPMENT continuous loop stopped")
    }

    private fun doOcidLookup() {
        val existingOcids = artifactStore.listStoredKeys(ExternalApiEndpoint.OCID_LOOKUP)
        if (existingOcids.isNotEmpty()) {
            log.info("[Scheduler] OCID lookup already done ({} files), skipping", existingOcids.size)
            return
        }

        val rateLimiter = newRateLimiter(ocidLookupPermitsPerSecond)

        val igns = csvReader.readAll()
        if (igns.isEmpty()) {
            log.warn("[Scheduler] no IGNs to process")
            return
        }

        log.info("[Scheduler] ========== OCID lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, store={}",
            igns.size, ocidLookupPermitsPerSecond, batchSize, storeBasePath,
        )

        val start = Instant.now()
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failCount = java.util.concurrent.atomic.AtomicInteger(0)
        val storedCount = java.util.concurrent.atomic.AtomicInteger(0)
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
        val existingBasic = artifactStore.listStoredKeys(ExternalApiEndpoint.CHARACTER_BASIC)
        if (existingBasic.isNotEmpty()) {
            log.info("[Scheduler] CHARACTER_BASIC already done ({} files), skipping", existingBasic.size)
            return
        }

        val entries = ocidCache.get().entries.toList()
        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping CHARACTER_BASIC")
            return
        }

        val runId = newRunId()
        val endpoint = "character-basic"
        val config = chunkingProperties.configFor(endpoint)
        val runDir = Paths.get(storeBasePath, "runs", runId)
        val sink = ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = endpoint,
            maxRecords = config.maxRecords,
            maxUncompressedBytes = config.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = eventPublisher,
        )

        val rateLimiter = newRateLimiter(permitsPerSecond)

        log.info("[Scheduler] ========== CHARACTER_BASIC lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, chunk={}records/{}bytes, runId={}",
            entries.size, permitsPerSecond, batchSize, config.maxRecords, config.maxUncompressedBytes, runId,
        )

        val start = Instant.now()
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failCount = java.util.concurrent.atomic.AtomicInteger(0)
        var processed = 0
        var lastProgressLog = 0

        try {
            while (processed < entries.size) {
                val permits = acquirePermits(rateLimiter, entries.size - processed)
                if (permits == 0) continue

                val chunk = entries.subList(processed, processed + permits)
                processed += permits

                val futures = chunk.map { (ign, ocid) ->
                    executor.submit(
                        Callable {
                            try {
                                val bodyBytes = clientPort.fetch(
                                    ExternalApiProvider.NEXON,
                                    ExternalApiEndpoint.CHARACTER_BASIC,
                                    ocid,
                                ).join()
                                sink.submit(
                                    SnapshotChunkRecord.Success(
                                        key = ocid,
                                        endpoint = endpoint,
                                        keyType = "OCID",
                                        httpStatus = 200,
                                        fetchedAt = Instant.now(),
                                        bodyBytes = bodyBytes,
                                    ),
                                )
                                successCount.incrementAndGet()
                            } catch (ex: Exception) {
                                val httpStatus = extractHttpStatus(ex)
                                sink.submit(
                                    SnapshotChunkRecord.Failure(
                                        key = ocid,
                                        endpoint = endpoint,
                                        keyType = "OCID",
                                        httpStatus = httpStatus,
                                        fetchedAt = Instant.now(),
                                        errorMessage = ex.message ?: "unknown",
                                    ),
                                )
                                failCount.incrementAndGet()
                            }
                        },
                    )
                }

                futures.forEach { it.get() }

                val progress = successCount.get() + failCount.get()
                if (progress - lastProgressLog >= 5000) {
                    lastProgressLog = progress
                    logProgress("CHARACTER_BASIC", progress, entries.size, successCount.get(), failCount.get(), start)
                }
            }
        } finally {
            sink.close()
        }

        logSummary("CHARACTER_BASIC", entries.size, successCount.get(), successCount.get(), failCount.get(), start)
    }

    private fun doItemEquipmentLookup(entries: List<Map.Entry<String, String>>) {
        val runId = newRunId()
        val endpoint = "item-equipment"
        val config = chunkingProperties.configFor(endpoint)
        val runDir = Paths.get(storeBasePath, "runs", runId)
        val sink = ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = endpoint,
            maxRecords = config.maxRecords,
            maxUncompressedBytes = config.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = eventPublisher,
        )

        val rateLimiter = newRateLimiter(permitsPerSecond)

        log.info("[Scheduler] ========== ITEM_EQUIPMENT lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, chunk={}records/{}bytes, runId={}",
            entries.size, permitsPerSecond, batchSize, config.maxRecords, config.maxUncompressedBytes, runId,
        )

        val start = Instant.now()
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failCount = java.util.concurrent.atomic.AtomicInteger(0)
        var processed = 0
        var lastProgressLog = 0

        try {
            while (processed < entries.size) {
                val permits = acquirePermits(rateLimiter, entries.size - processed)
                if (permits == 0) continue

                val chunk = entries.subList(processed, processed + permits)
                processed += permits

                val futures = chunk.map { (ign, ocid) ->
                    executor.submit(
                        Callable {
                            try {
                                val bodyBytes = clientPort.fetch(
                                    ExternalApiProvider.NEXON,
                                    ExternalApiEndpoint.ITEM_EQUIPMENT,
                                    ocid,
                                ).join()
                                sink.submit(
                                    SnapshotChunkRecord.Success(
                                        key = ocid,
                                        endpoint = endpoint,
                                        keyType = "OCID",
                                        httpStatus = 200,
                                        fetchedAt = Instant.now(),
                                        bodyBytes = bodyBytes,
                                    ),
                                )
                                successCount.incrementAndGet()
                            } catch (ex: Exception) {
                                val httpStatus = extractHttpStatus(ex)
                                sink.submit(
                                    SnapshotChunkRecord.Failure(
                                        key = ocid,
                                        endpoint = endpoint,
                                        keyType = "OCID",
                                        httpStatus = httpStatus,
                                        fetchedAt = Instant.now(),
                                        errorMessage = ex.message ?: "unknown",
                                    ),
                                )
                                failCount.incrementAndGet()
                            }
                        },
                    )
                }

                futures.forEach { it.get() }

                val progress = successCount.get() + failCount.get()
                if (progress - lastProgressLog >= 5000) {
                    lastProgressLog = progress
                    logProgress("ITEM_EQUIPMENT", progress, entries.size, successCount.get(), failCount.get(), start)
                }
            }
        } finally {
            sink.close()
        }

        logSummary("ITEM_EQUIPMENT", entries.size, successCount.get(), successCount.get(), failCount.get(), start)
    }

    private fun loadOcidCache() {
        val keys = artifactStore.listStoredKeys(ExternalApiEndpoint.OCID_LOOKUP)
        if (keys.isEmpty()) {
            log.info("[Scheduler] no stored OCIDs found, cache empty")
            return
        }

        val cache = mutableMapOf<String, String>()
        for (key in keys) {
            try {
                val bytes = artifactStore.read(ExternalApiEndpoint.OCID_LOOKUP, key)
                if (bytes != null) {
                    val node = objectMapper.readTree(bytes)
                    val ocid = node.get("ocid")?.asText()
                    if (ocid != null) {
                        cache[key] = ocid
                    }
                }
            } catch (ex: Exception) {
                log.debug("[Scheduler] failed to parse OCID for key={}", key)
            }
        }
        ocidCache.set(cache)
        log.info("[Scheduler] OCID cache loaded: {} entries", cache.size)
    }

    private fun acquireLock(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (running.compareAndSet(false, true)) return true
            Thread.sleep(Duration.ofMillis(500))
        }
        return false
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

    private fun newRunId(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
        return formatter.format(Instant.now())
    }

    private fun extractHttpStatus(ex: Throwable): Int {
        val cause = if (ex is java.util.concurrent.CompletionException) ex.cause else ex
        return when (cause) {
            is org.springframework.web.reactive.function.client.WebClientResponseException -> cause.statusCode.value()
            else -> 0
        }
    }

    private fun logProgress(phase: String, progress: Int, total: Int, stored: Int, fails: Int, start: Instant) {
        val elapsedSec = Duration.between(start, Instant.now()).toMillis() / 1000.0
        val rate = if (elapsedSec > 0) "%.0f".format(progress / elapsedSec) else "?"
        log.info(
            "[Scheduler] {}: {}/{} (success={}, fail={}, rate={}files/s, elapsed={}s)",
            phase, progress, total, stored, fails, rate, elapsedSec.toLong(),
        )
    }

    private fun logSummary(phase: String, total: Int, success: Int, stored: Int, fails: Int, start: Instant) {
        val elapsedSec = Duration.between(start, Instant.now()).toMillis() / 1000.0
        val rate = if (elapsedSec > 0) "%.0f".format(total / elapsedSec) else "?"
        log.info("[Scheduler] ========== {} complete ==========", phase)
        log.info(
            "[Scheduler] result: total={}, success={}, fail={}, elapsed={}s, avgRate={}files/s",
            total, success, fails, elapsedSec.toLong(), rate,
        )
    }

    @PreDestroy
    fun onDestroy() {
        log.info("[Scheduler] shutdown requested")
        shutdown.set(true)
        executor.close()
    }
}
