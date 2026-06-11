package maple.externalapi.scheduler.phase

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.infrastructure.external.NexonAuthClient
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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
 * Caller (ExternalApiScheduler) 는 `runBlocking { ocidLookupPhase.execute(workerExecutor, runKey) }` (multi-threaded VT, short-lived).
 *
 * <p>VS2 migration (Task 7): input chunks and output OCID mapping are read/written via
 * [ObjectStorage] using string keys. `runKey: String` replaces the prior `runDir: Path` argument.
 * The `nexonAuthClient` is held for future callers (per-key authentication); the current
 * per-IGN lookup path goes through [ExternalApiClientPort].
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
    @Qualifier("ocidLookupSnapshotPublisher")
    private val eventPublisher: SnapshotChunkEventPublisher,
    private val objectStorage: ObjectStorage,
    private val nexonAuthClient: NexonAuthClient,
) {
    private val log = LoggerFactory.getLogger(OcidLookupPhase::class.java)

    /**
     * External entry point. Caller (ExternalApiScheduler) uses:
     * `runBlocking { ocidLookupPhase.execute(workerExecutor, runKey) }`
     */
    suspend fun execute(workerExecutor: ExecutorService, runKey: String) {
        val mappingDir = "ocid-mapping"
        deleteOldMappingFiles(mappingDir)

        val igns = readCharacterNamesFromChunks(runKey)
        if (igns.isEmpty()) {
            log.warn("[Scheduler] no character names from chunks: {}", runKey)
            return
        }
        log.info("[Scheduler] read {} character names from chunks: {}", igns.size, runKey)

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(ocidLookupPermitsPerSecond)

        log.info("[Scheduler] ========== OCID lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, store=ObjectStorage",
            igns.size, ocidLookupPermitsPerSecond, batchSize,
        )

        val start = Instant.now()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val lastProgressLog = AtomicInteger(0)
        val results: MutableList<String> = Collections.synchronizedList(mutableListOf())

        processBatch(
            workerExecutor = workerExecutor,
            rateLimiter = rateLimiter,
            runKey = runKey,
            igns = igns,
            processed = 0,
            successCount = successCount,
            failCount = failCount,
            lastProgressLog = lastProgressLog,
            results = results,
            start = start,
        )

        val runId = runKey.removePrefix("runs/").substringBefore('/')
        writeMappingGzipped(mappingDir, results, runId)
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
                manifestPath = "$mappingDir/ocid-mapping-$runId.jsonl.gz",
                totalRecords = results.size,
                totalFailed = failCount.get(),
                chunkCount = 1,
                startedAt = start,
                finishedAt = Instant.now(),
                createdAt = Instant.now(),
            ),
        )
    }

    /**
     * GZIP decompress + per-line JSON parse. CPU-bound → `Dispatchers.Default`.
     */
    suspend fun readCharacterNamesFromChunks(runKey: String): List<String> = withContext(Dispatchers.Default) {
        val prefix = "$runKey/ranking-overall/chunks"
        val names = linkedSetOf<String>()
        for (obj in objectStorage.listByPrefix(prefix)) {
            if (!obj.key.endsWith(".jsonl.gz")) continue
            GZIPInputStream(BufferedInputStream(objectStorage.getStream(obj.key))).bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.isNotBlank()) {
                        val node = objectMapper.readTree(line)
                        val key = node.get("key")?.asText()
                        if (key != null) names.add(key)
                    }
                }
            }
        }
        names.toList()
    }

    private fun deleteOldMappingFiles(mappingDir: String) {
        val total = objectStorage.deleteByPrefix("$mappingDir/")
        log.info("[Scheduler] deleted {} old OCID mapping objects in {}/", total, mappingDir)
    }

    private fun writeMappingGzipped(mappingDir: String, results: List<String>, runId: String) {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(BufferedOutputStream(out)).use { gz ->
            for (r in results) {
                gz.write(r.toByteArray())
                gz.write('\n'.code)
            }
        }
        val key = "$mappingDir/ocid-mapping-$runId.jsonl.gz"
        objectStorage.put(key, out.toByteArray())
        log.info("[Scheduler] wrote {} OCID mappings to {} ({} bytes)", results.size, key, out.size())
    }

    /**
     * Iterative batch processor. async + awaitAll for parallel CPU offload.
     * Each `async` runs on caller dispatcher (IO from runBlocking → no explicit = inherited).
     * CPU work inside `fetchAndCollectOcidAsync` switches to `Dispatchers.Default`.
     *
     * <p>Originally recursive (0344c62b7). Reverted to iteration: recursive call on
     * rate-limiter permit exhaustion caused StackOverflowError when the bucket drained
     * faster than the refill window (#1217 follow-up).
     */
    private suspend fun processBatch(
        workerExecutor: ExecutorService,
        rateLimiter: io.github.bucket4j.Bucket,
        runKey: String,
        igns: List<String>,
        processed: Int,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
        lastProgressLog: AtomicInteger,
        results: MutableList<String>,
        start: Instant,
    ) {
        var current = processed
        while (current < igns.size) {
            val permits = SchedulerPhaseUtils.acquirePermits(rateLimiter, batchSize, igns.size - current)
            if (permits == 0) {
                // bucket drained — yield to dispatcher so other coroutines run,
                // then retry on next iteration (no artificial delay)
                yield()
                continue
            }

            val chunk = igns.subList(current, current + permits)
            coroutineScope {
                chunk.map { ign ->
                    async(Dispatchers.IO) {
                        fetchAndCollectOcidAsync(ign, workerExecutor, successCount, failCount, results)
                    }
                }.awaitAll()
            }

            current += permits

            val progress = successCount.get() + failCount.get()
            if (progress - lastProgressLog.get() >= 5000) {
                lastProgressLog.set(progress)
                SchedulerPhaseUtils.logProgress(
                    "OCID lookup", progress, igns.size,
                    successCount.get(), failCount.get(), start,
                )
            }
        }
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
