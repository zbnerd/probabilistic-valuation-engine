package maple.externalapi.scheduler.phase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.parser.OcidResponseParser
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.reader.CharacterNameReader
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.util.StringMaskingUtils.maskIgn
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.zip.GZIPOutputStream

/** Emit a progress log every N items processed. 5,000 chosen to keep log volume under ~3 lines/sec/chunk. */
private const val PROGRESS_LOG_INTERVAL: Int = 5_000

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class OcidLookupPhase(
    private val clientPort: ExternalApiClientPort,
    private val ocidResponseParser: OcidResponseParser,
    private val characterNameReader: CharacterNameReader,
    @Value("\${external-api.rate-limit.ocid-lookup-permits-per-second:400}")
    private val ocidLookupPermitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
    @Qualifier("ocidLookupSnapshotPublisher")
    private val eventPublisher: SnapshotChunkEventPublisher,
    @Value("\${external-api.concurrency.max-in-flight:100}")
    maxInFlight: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(OcidLookupPhase::class.java)
    private val semaphore = Semaphore(maxInFlight)
    private val maxInFlight = maxInFlight

    fun execute(workerExecutor: ExecutorService, rankingRunDir: Path): CompletableFuture<Path?> {
        val mappingDir = Path.of(storeBasePath).resolve("ocid-mapping")
        deleteOldMappingFiles(mappingDir)

        val igns = readCharacterNamesFromChunks(rankingRunDir)
        if (igns.isEmpty()) {
            log.warn("[Scheduler] no character names from ranking chunks: {}", rankingRunDir)
            return CompletableFuture.completedFuture(null)
        }
        log.info("[Scheduler] read {} character names from ranking chunks: {}", igns.size, rankingRunDir)

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(ocidLookupPermitsPerSecond)

        log.info("[Scheduler] ========== OCID lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, maxInFlight={}, store={}",
            igns.size, ocidLookupPermitsPerSecond, batchSize, maxInFlight, storeBasePath,
        )

        val start = Instant.now(clock)
        val dispatcher = workerExecutor.asCoroutineDispatcher()
        val results = mutableListOf<String>()

        return CoroutineScope(dispatcher).future {
            val (successCount, failCount) = processBatchSuspend(rateLimiter, igns, results)

            val runId = SchedulerPhaseUtils.newRunId()
            val outputPath = writeGzipJsonl(mappingDir, results, runId)
            SchedulerPhaseUtils.logSummary("OCID lookup", igns.size, successCount, successCount, failCount, start)
            eventPublisher.publishRunCompleted(SnapshotRunCompletedEvent(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                endpoint = "ocid-lookup",
                manifestPath = "ocid-mapping/${outputPath.fileName}",
                totalRecords = results.size,
                totalFailed = failCount,
                chunkCount = 1,
                startedAt = start,
                finishedAt = Instant.now(clock),
                createdAt = Instant.now(clock),
            ))
            outputPath
        }
    }

    /**
     * Batch processing with coroutine-based parallelism and semaphore-gated concurrency.
     * Replaces recursive CF chain + AtomicInteger with while loop + local accumulators.
     */
    private suspend fun processBatchSuspend(
        rateLimiter: io.github.bucket4j.Bucket,
        igns: List<String>,
        results: MutableList<String>,
    ): Pair<Int, Int> {
        var processed = 0
        var successCount = 0
        var failCount = 0
        var lastProgressLog = 0
        val start = Instant.now(clock)

        while (processed < igns.size) {
            val permits = SchedulerPhaseUtils.acquirePermitsSuspend(rateLimiter, batchSize, igns.size - processed)
            if (permits == 0) continue // acquirePermitsSuspend already delays 100ms

            val chunk = igns.subList(processed, processed + permits)
            val batchResults = coroutineScope {
                chunk.map { ign ->
                    async {
                        runCatching { fetchOcid(ign) }.getOrNull()
                    }
                }.awaitAll()
            }

            val batchSuccess = batchResults.filterNotNull()
            results.addAll(batchSuccess)
            successCount += batchSuccess.size
            failCount += chunk.size - batchSuccess.size

            processed += permits

            val progress = successCount + failCount
            if (progress - lastProgressLog >= PROGRESS_LOG_INTERVAL) {
                lastProgressLog = progress
                SchedulerPhaseUtils.logProgress("OCID lookup", progress, igns.size, successCount, failCount, start)
            }
        }
        return successCount to failCount
    }

    /**
     * Fetches OCID for a single IGN. Coroutine Semaphore gates concurrency with
     * 10s timeout to prevent indefinite hang on semaphore acquisition.
     * Replaces tryAcquireWithBackoff() + Thread.sleep with structured suspension.
     */
    private suspend fun fetchOcid(ign: String): String? {
        return withTimeoutOrNull(10_000L) {
            semaphore.withPermit {
                val data = clientPort.fetch(
                    ExternalApiProvider.NEXON,
                    ExternalApiEndpoint.OCID_LOOKUP,
                    ign,
                ).await()
                val ocid = ocidResponseParser.extractOcid(String(data))
                if (ocid != null) {
                    String(ocidResponseParser.serializeMapping(ign, ocid), Charsets.UTF_8)
                } else {
                    log.warn("[OCID] null ocid for ign={}", maskIgn(ign))
                    null
                }
            }
        }
    }

    fun readCharacterNamesFromChunks(runDir: Path): List<String> {
        val chunksDir = runDir.resolve("ranking-overall").resolve("chunks")
        return characterNameReader.readDistinctKeys(chunksDir)
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
}
