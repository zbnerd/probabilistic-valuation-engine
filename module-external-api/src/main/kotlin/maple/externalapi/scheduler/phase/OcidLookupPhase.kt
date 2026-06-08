package maple.externalapi.scheduler.phase

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import maple.expectation.common.event.SnapshotRunCompletedEvent
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * OCID lookup phase scheduler (Issue #1128).
 *
 * <p>CPU-bound 작업 (JSON parse/serialize, GZIP decompress) 을 `Dispatchers.Default` 로 offload.
 * `readCharacterNamesFromChunks()` + `processBatch()` + `fetchAndCollectOcidAsync()` 는 `suspend fun` 으로 refactor.
 * Caller (ExternalApiScheduler) 는 `runBlocking { ocidLookupPhase.execute(workerExecutor, rankingRunDir) }` (multi-threaded VT, short-lived).
 */
@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class OcidLookupPhase(
    private val clientPort: ExternalApiClientPort,
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.rate-limit.ocid-lookup-permits-per-second:400}")
    private val ocidLookupPermitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
    @Qualifier("ocidLookupSnapshotPublisher")
    private val eventPublisher: SnapshotChunkEventPublisher,
) {
    private val log = LoggerFactory.getLogger(OcidLookupPhase::class.java)

    /**
     * External entry point. Caller (ExternalApiScheduler) uses:
     * `runBlocking { ocidLookupPhase.execute(workerExecutor, rankingRunDir) }`
     */
    suspend fun execute(workerExecutor: ExecutorService, rankingRunDir: Path): Path? {
        val mappingDir = Path.of(storeBasePath).resolve("ocid-mapping")
        deleteOldMappingFiles(mappingDir)

        val igns = readCharacterNamesFromChunks(rankingRunDir)
        if (igns.isEmpty()) {
            log.warn("[Scheduler] no character names from ranking chunks: {}", rankingRunDir)
            return null
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
        val lastProgressLog = AtomicInteger(0)
        val results: MutableList<String> = Collections.synchronizedList(mutableListOf())

        processBatch(
            workerExecutor = workerExecutor,
            rateLimiter = rateLimiter,
            igns = igns,
            processed = 0,
            successCount = successCount,
            failCount = failCount,
            lastProgressLog = lastProgressLog,
            results = results,
            start = start,
        )

        val runId = SchedulerPhaseUtils.newRunId()
        val outputPath = writeGzipJsonl(mappingDir, results, runId)
        SchedulerPhaseUtils.logSummary(
            "OCID lookup",
            igns.size,
            successCount.get(),
            successCount.get(),
            failCount.get(),
            start,
        )
        eventPublisher.publishRunCompleted(
            SnapshotRunCompletedEvent(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                endpoint = "ocid-lookup",
                manifestPath = "ocid-mapping/${outputPath.fileName}",
                totalRecords = results.size,
                totalFailed = failCount.get(),
                chunkCount = 1,
                startedAt = start,
                finishedAt = Instant.now(),
                createdAt = Instant.now(),
            ),
        )
        return outputPath
    }

    /**
     * GZIP decompress + per-line JSON parse. CPU-bound → `Dispatchers.Default`.
     */
    suspend fun readCharacterNamesFromChunks(runDir: Path): List<String> = withContext(Dispatchers.Default) {
        val chunksDir = runDir.resolve("ranking-overall").resolve("chunks")
        if (!Files.exists(chunksDir)) return@withContext emptyList()

        val names = linkedSetOf<String>()
        Files.list(chunksDir).use { stream ->
            stream.filter { it.toString().endsWith(".jsonl.gz") }
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
        }
        names.toList()
    }

    private fun deleteOldMappingFiles(mappingDir: Path) {
        if (!Files.exists(mappingDir)) return
        var deleted = 0
        Files.list(mappingDir).use { stream ->
            stream.filter { it.toString().endsWith(".jsonl.gz") }
                .forEach { file ->
                    Files.deleteIfExists(file)
                    deleted++
                }
        }
        log.info("[Scheduler] deleted {} old OCID mapping files in {}", deleted, mappingDir)
    }

    private fun writeGzipJsonl(mappingDir: Path, results: List<String>, runId: String): Path {
        Files.createDirectories(mappingDir)
        val outputPath = mappingDir.resolve("ocid-mapping-$runId.jsonl.gz")
        val tempFile = Files.createTempFile("ocid-mapping-", ".jsonl")
        tempFile.toFile().deleteOnExit()
        Files.write(tempFile, results)
        GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(outputPath))).use { gzip ->
            Files.copy(tempFile, gzip)
        }
        Files.deleteIfExists(tempFile)

        val size = Files.size(outputPath)
        log.info("[Scheduler] wrote {} OCID mappings to {} ({} bytes)", results.size, outputPath, size)
        return outputPath
    }

    /**
     * Recursive batch processor. async + awaitAll for parallel CPU offload.
     * Each `async` runs on caller dispatcher (IO from runBlocking → no explicit = inherited).
     * CPU work inside `fetchAndCollectOcidAsync` switches to `Dispatchers.Default`.
     */
    private suspend fun processBatch(
        workerExecutor: ExecutorService,
        rateLimiter: io.github.bucket4j.Bucket,
        igns: List<String>,
        processed: Int,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
        lastProgressLog: AtomicInteger,
        results: MutableList<String>,
        start: Instant,
    ) {
        if (processed >= igns.size) return

        val permits = SchedulerPhaseUtils.acquirePermits(rateLimiter, batchSize, igns.size - processed)
        if (permits == 0) {
            return processBatch(
                workerExecutor, rateLimiter, igns, processed,
                successCount, failCount, lastProgressLog, results, start,
            )
        }

        val chunk = igns.subList(processed, processed + permits)
        coroutineScope {
            chunk.map { ign ->
                async(Dispatchers.IO) {
                    fetchAndCollectOcidAsync(ign, workerExecutor, successCount, failCount, results)
                }
            }.awaitAll()
        }

        val progress = successCount.get() + failCount.get()
        if (progress - lastProgressLog.get() >= 5000) {
            lastProgressLog.set(progress)
            SchedulerPhaseUtils.logProgress(
                "OCID lookup", progress, igns.size,
                successCount.get(), failCount.get(), start,
            )
        }
        processBatch(
            workerExecutor, rateLimiter, igns, processed + permits,
            successCount, failCount, lastProgressLog, results, start,
        )
    }

    /**
     * Per-ign OCID fetch + JSON parse/serialize. HTTP call on IO, CPU offload via withContext(Default).
     */
    private suspend fun fetchAndCollectOcidAsync(
        ign: String,
        workerExecutor: ExecutorService,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
        results: MutableList<String>,
    ) {
        try {
            val data = withContext(Dispatchers.IO) {
                clientPort.fetch(
                    ExternalApiProvider.NEXON,
                    ExternalApiEndpoint.OCID_LOOKUP,
                    ign,
                ).await()
            }

            val (ocid, json) = withContext(Dispatchers.Default) {
                val ocidVal = objectMapper.readTree(data).get("ocid")?.asText()
                if (ocidVal != null) {
                    val jsonVal = String(
                        objectMapper.writeValueAsBytes(mapOf("userIgn" to ign, "ocid" to ocidVal)),
                    )
                    ocidVal to jsonVal
                } else {
                    null to null
                }
            }

            if (ocid != null && json != null) {
                results.add(json)
                successCount.incrementAndGet()
            }
        } catch (ex: Exception) {
            failCount.incrementAndGet()
        }
    }
}
