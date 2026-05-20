package maple.externalapi.scheduler.phase

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class OcidLookupPhase(
    private val clientPort: ExternalApiClientPort,
    private val artifactStore: ExternalApiArtifactStorePort,
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.rate-limit.ocid-lookup-permits-per-second:400}")
    private val ocidLookupPermitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:/data/external-api}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(OcidLookupPhase::class.java)

    fun execute(workerExecutor: ExecutorService, rankingRunDir: Path): CompletableFuture<Void> {
        val deleted = artifactStore.deleteAll(ExternalApiEndpoint.OCID_LOOKUP)
        log.info("[Scheduler] deleted {} existing OCID files", deleted)

        val igns = readCharacterNamesFromChunks(rankingRunDir)
        if (igns.isEmpty()) {
            log.warn("[Scheduler] no character names from ranking chunks: {}", rankingRunDir)
            return CompletableFuture.completedFuture(null)
        }
        log.info("[Scheduler] read {} character names from ranking chunks: {}", igns.size, rankingRunDir)

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(ocidLookupPermitsPerSecond)

        log.info("[Scheduler] ========== OCID lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, store={}",
            igns.size, ocidLookupPermitsPerSecond, batchSize, storeBasePath,
        )

        val start = Instant.now()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val storedCount = AtomicInteger(0)
        val lastProgressLog = AtomicInteger(0)

        return processBatch(
            workerExecutor = workerExecutor,
            rateLimiter = rateLimiter,
            igns = igns,
            processed = 0,
            successCount = successCount,
            failCount = failCount,
            storedCount = storedCount,
            lastProgressLog = lastProgressLog,
            start = start,
        ).whenComplete { _, _ ->
            SchedulerPhaseUtils.logSummary("OCID lookup", igns.size, successCount.get(), storedCount.get(), failCount.get(), start)
        }
    }

    fun readCharacterNamesFromChunks(runDir: Path): List<String> {
        val chunksDir = runDir.resolve("ranking-overall").resolve("chunks")
        if (!Files.exists(chunksDir)) return emptyList()

        val names = linkedSetOf<String>()
        Files.list(chunksDir)
            .filter { it.toString().endsWith(".jsonl.gz") }
            .sorted()
            .forEach { chunkFile ->
                GZIPInputStream(BufferedInputStream(Files.newInputStream(chunkFile))).bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        if (line.isNotBlank()) {
                            val node = objectMapper.readTree(line)
                            val key = node.get("key")?.asText()
                            if (key != null) names.add(key)
                        }
                    }
                }
            }
        return names.toList()
    }

    private fun processBatch(
        workerExecutor: ExecutorService,
        rateLimiter: io.github.bucket4j.Bucket,
        igns: List<String>,
        processed: Int,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
        storedCount: AtomicInteger,
        lastProgressLog: AtomicInteger,
        start: Instant,
    ): CompletableFuture<Void> {
        if (processed >= igns.size) {
            return CompletableFuture.completedFuture(null)
        }

        val permits = SchedulerPhaseUtils.acquirePermits(rateLimiter, batchSize, igns.size - processed)
        if (permits == 0) {
            return processBatch(workerExecutor, rateLimiter, igns, processed, successCount, failCount, storedCount, lastProgressLog, start)
        }

        val chunk = igns.subList(processed, processed + permits)
        val futures = chunk.map { ign ->
            fetchAndStoreOcidAsync(ign, workerExecutor, successCount, failCount, storedCount)
        }

        return CompletableFuture.allOf(*futures.toTypedArray()).thenCompose {
            val progress = successCount.get() + failCount.get()
            if (progress - lastProgressLog.get() >= 5000) {
                lastProgressLog.set(progress)
                SchedulerPhaseUtils.logProgress("OCID lookup", progress, igns.size, storedCount.get(), failCount.get(), start)
            }
            processBatch(workerExecutor, rateLimiter, igns, processed + permits, successCount, failCount, storedCount, lastProgressLog, start)
        }
    }

    private fun fetchAndStoreOcidAsync(
        ign: String,
        workerExecutor: ExecutorService,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
        storedCount: AtomicInteger,
    ): CompletableFuture<Void> =
        clientPort.fetch(
            ExternalApiProvider.NEXON,
            ExternalApiEndpoint.OCID_LOOKUP,
            ign,
        )
            .thenAcceptAsync({ data ->
                artifactStore.store(ExternalApiEndpoint.OCID_LOOKUP, ign, data)
                successCount.incrementAndGet()
                storedCount.incrementAndGet()
            }, workerExecutor)
            .handle { _, ex ->
                if (ex != null) failCount.incrementAndGet()
                null
            }
}
