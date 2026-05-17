package maple.externalapi.scheduler.phase

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.reader.UserIgnCsvReader
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class OcidLookupPhase(
    private val clientPort: ExternalApiClientPort,
    private val csvReader: UserIgnCsvReader,
    private val artifactStore: ExternalApiArtifactStorePort,
    private val executor: LogicExecutor,
    @Value("\${external-api.rate-limit.ocid-lookup-permits-per-second:400}")
    private val ocidLookupPermitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:/data/external-api}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(OcidLookupPhase::class.java)

    fun execute(executor: ExecutorService) {
        val existingOcids = artifactStore.listStoredKeys(ExternalApiEndpoint.OCID_LOOKUP)
        if (existingOcids.isNotEmpty()) {
            log.info("[Scheduler] OCID lookup already done ({} files), skipping", existingOcids.size)
            return
        }

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(ocidLookupPermitsPerSecond)

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
            val permits = SchedulerPhaseUtils.acquirePermits(rateLimiter, batchSize, igns.size - processed)
            if (permits == 0) continue

            val chunk = igns.subList(processed, processed + permits)
            processed += permits

            val futures = chunk.map { ign ->
                executor.submit(Callable { fetchAndStoreOcid(ign, successCount, failCount, storedCount) })
            }

            futures.forEach { it.get() }

            val progress = successCount.get() + failCount.get()
            if (progress - lastProgressLog >= 5000) {
                lastProgressLog = progress
                SchedulerPhaseUtils.logProgress("OCID lookup", progress, igns.size, storedCount.get(), failCount.get(), start)
            }
        }

        SchedulerPhaseUtils.logSummary("OCID lookup", igns.size, successCount.get(), storedCount.get(), failCount.get(), start)
    }

    private fun fetchAndStoreOcid(
        ign: String,
        successCount: java.util.concurrent.atomic.AtomicInteger,
        failCount: java.util.concurrent.atomic.AtomicInteger,
        storedCount: java.util.concurrent.atomic.AtomicInteger,
    ) {
        executor.executeWithFallback(
            { performOcidFetch(ign, successCount, storedCount) },
            { failCount.incrementAndGet() },
            TaskContext.of("OcidLookup", "FetchStore", ign),
        )
    }

    private fun performOcidFetch(
        ign: String,
        successCount: java.util.concurrent.atomic.AtomicInteger,
        storedCount: java.util.concurrent.atomic.AtomicInteger,
    ) {
        val data = clientPort.fetch(
            ExternalApiProvider.NEXON,
            ExternalApiEndpoint.OCID_LOOKUP,
            ign,
        ).join()
        val payloadRef = artifactStore.store(ExternalApiEndpoint.OCID_LOOKUP, ign, data)
        successCount.incrementAndGet()
        if (payloadRef != null) storedCount.incrementAndGet()
    }
}
