package maple.externalapi.scheduler.phase

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.expectation.infrastructure.external.NexonAuthClient
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.PhaseStoppedException
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import maple.common.parser.StreamingChunkParser
import maple.externalapi.metrics.ChunkParserMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * OCID lookup phase scheduler (Issue #1128).
 *
 * <p>CPU-bound 작업 (JSON parse/serialize, GZIP decompress) 을 `Dispatchers.Default` 로 offload.
 * `readCharacterNamesFromChunks()` + `processBatch()` + `fetchAndCollectOcidAsync()` 는 `suspend fun` 으로 refactor.
 * Caller (ExternalApiScheduler) 는 `runBlocking { ocidLookupPhase.execute(workerExecutor, runKey) }` (multi-threaded VT, short-lived).
 *
 * <p>VS2 migration (Task 7): input chunks and output OCID mapping are read/written via
 * [ObjectStorage] using string keys. `runKey: String` replaces the prior `runDir: Path` argumento.
 * The `nexonAuthClient` is held for future callers (per-key authentication); the current
 * per-IGN lookup path goes through [ExternalApiClientPort].
 *
 * <p>Streaming write: each successful mapping is pushed to a [Channel] and consumed by a
 * single writer coroutine that pipes bytes into a GZIPOutputStream wrapping
 * [ObjectStorage.putStreamMultipart]. The previous implementation accumulated all 600K+
 * entries in a `MutableList<String>` until the end of the phase — that held ~120MB of JSON
 * strings in heap for the entire OCID run and pushed the JVM over its 1GB ceiling when
 * combined with other in-flight state. Streaming keeps the heap footprint bounded to the
 * pipe buffer (64KB) plus the GZIPOutputStream internal buffer.
 *
 * <p>Issue #1319: migrated from deprecated `putStream` (heap-draining `readBytes()` inside
 * the impl) to `putStreamMultipart` (S3AsyncClient chunked transfer on Minio, temp-file +
 * `putFile` on LocalFs virtual-thread). Heap peak: bounded to pipe (64KB) instead of the
 * full OCID mapping chunk (~120MB).
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
    private val stopSignal: PhaseStopSignal,
    private val streamingChunkParser: StreamingChunkParser,
    private val chunkParserMetrics: ChunkParserMetrics,
) {
    private val log = LoggerFactory.getLogger(OcidLookupPhase::class.java)

    /**
     * External entry point. Caller (ExternalApiScheduler) uses:
     * `runBlocking { ocidLookupPhase.execute(workerExecutor, runKey, runId) }`
     */
    suspend fun execute(workerExecutor: ExecutorService, runKey: String, runId: String) {
        val mappingDir = "ocid-mapping"
        deleteOldMappingFiles(mappingDir, runId)

        val igns = readCharacterNamesFromChunks(runKey)
        if (igns.isEmpty()) {
            log.warn("[Scheduler] no character names from chunks: {}", runKey)
            return
        }
        log.info("[Scheduler] read {} character names from chunks: {}", igns.size, runKey)

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(ocidLookupPermitsPerSecond)

        log.info("[Scheduler] ========== OCID lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, store=ObjectStorage (streaming)",
            igns.size, ocidLookupPermitsPerSecond, batchSize,
        )

        val start = Instant.now()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val lastProgressLog = AtomicInteger(0)
        val key = "$mappingDir/ocid-mapping-$runId.jsonl.gz"

        // Channel + writer coroutine: producers (per-ign fetch) send strings,
        // a single consumer coroutine gzips + writes to a temp file.
        // Channel.BUFFERED applies backpressure when the writer can't keep up.
        val resultsChannel = Channel<String>(Channel.BUFFERED)

        // Drain to temp file, then upload via putFileAsync. We previously used
        // PipedInputStream/OutputStream + putStreamMultipart for streaming
        // chunked transfer, but the SDK reads the InputStream on a background
        // thread that races the writer — `PipedInputStream` throws "Read end
        // dead" the moment the writer thread exits (checked via
        // `readSide.isAlive()`). A temp file avoids the thread coordination
        // entirely: writer writes to disk, upload reads from disk.
        val tempFile = Files.createTempFile("ocid-mapping-${runId}-", ".jsonl.gz.tmp")
        var uploadFuture: CompletableFuture<PutResult>? = null
        try {
            coroutineScope {
                // Writer: CPU-bound (GZIPOutputStream.compress) on
                // Dispatchers.Default per architecture-guardrails.md rule 9.
                val writerJob = launch(Dispatchers.Default) {
                    val gz = GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(tempFile)))
                    try {
                        for (entry in resultsChannel) {
                            gz.write(entry.toByteArray())
                            gz.write('\n'.code)
                        }
                    } finally {
                        runCatching { gz.close() }
                    }
                }

                processBatch(
                    workerExecutor = workerExecutor,
                    rateLimiter = rateLimiter,
                    runKey = runKey,
                    igns = igns,
                    processed = 0,
                    successCount = successCount,
                    failCount = failCount,
                    lastProgressLog = lastProgressLog,
                    resultsSink = resultsChannel,
                    start = start,
                )

                resultsChannel.close()
                writerJob.join()

                // Upload the finalized temp file via putFileAsync
                // (S3TransferManager multipart | LocalFs temp-file). Returns a
                // CF that resolves when the upload completes.
                uploadFuture = objectStorage.putFileAsync(key, tempFile)
            }
            // Wait for the upload to complete before the temp file is deleted
            // (MinIO S3TransferManager reads from the file during upload).
            uploadFuture?.await()
        } finally {
            // Clean up the temp file once the upload completes — or fails.
            // For MinIO success path the S3TransferManager uploads from the
            // file then the whenComplete callback in putFileAsync removes
            // it; for LocalFs the impl already moved the file into place.
            // The delete here is a safety net for the MinIO success path.
            runCatching { Files.deleteIfExists(tempFile) }
        }
        log.info(
            "[Scheduler] streamed {} OCID mappings to {} (heap-bounded via pipe)",
            successCount.get(), key,
        )
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
                manifestPath = key,
                totalRecords = successCount.get(),
                totalFailed = failCount.get(),
                chunkCount = 1,
                startedAt = start,
                finishedAt = Instant.now(),
                createdAt = Instant.now(),
            ),
        )
    }

    /**
     * GZIP decompress + line-bounded JSONL parse. CPU-bound →
     * `Dispatchers.Default`. Uses [StreamingChunkParser] for
     * streaming parse; no manual readTree per line.
     */
    suspend fun readCharacterNamesFromChunks(runKey: String): List<String> =
        withContext(Dispatchers.Default) {
            val prefix = "$runKey/ranking-overall/chunks"
            val names = linkedSetOf<String>()
            val emitted = chunkParserMetrics.recordsEmitted("ranking_chunk_names")
            // Pre-register skipped counter so it appears in /actuator/prometheus from the start.
            chunkParserMetrics.recordsSkipped("ranking_chunk_names")
            val timer = chunkParserMetrics.parseDuration("ranking_chunk_names")
            val start = System.nanoTime()

            for (obj in objectStorage.listByPrefix(prefix)) {
                if (!obj.key.endsWith(".jsonl.gz")) continue
                val records = objectStorage.getStream(obj.key).use { stream ->
                    streamingChunkParser.parse(stream).toList()
                }
                for (record in records) {
                    emitted.increment()
                    val key = record["key"]?.toString()
                    if (!key.isNullOrBlank()) names.add(key)
                }
            }

            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
            log.info(
                "[OcidLookup] readCharacterNamesFromChunks key={} distinct={}",
                runKey, names.size,
            )
            names.toList()
        }

    /**
     * Delete old OCID mapping objects under [mappingDir], but PRESERVE the
     * current run's mapping file (Revision 5 of phase-trigger-endpoint plan).
     *
     * Previously this called `deleteByPrefix("$mappingDir/")` which deleted ALL
     * objects including the file we were about to write, causing a race where
     * sibling processes would see an empty mapping directory mid-run. Now we
     * list-then-delete-per-key, skipping the key that ends with
     * `ocid-mapping-$currentRunId.jsonl.gz`.
     */
    private fun deleteOldMappingFiles(mappingDir: String, currentRunId: String) {
        val prefix = "$mappingDir/"
        val preserveKey = "$mappingDir/ocid-mapping-$currentRunId.jsonl.gz"
        val objects = objectStorage.listByPrefix(prefix)
        var deleted = 0
        for (obj in objects) {
            if (obj.key == preserveKey) {
                log.info("[Scheduler] preserving current runId={} mapping {}", currentRunId, obj.key)
                continue
            }
            objectStorage.delete(obj.key)
            deleted++
        }
        log.info("[Scheduler] deleted {} old OCID mapping objects in {}/ (preserved current runId={})",
            deleted, mappingDir, currentRunId)
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
        resultsSink: SendChannel<String>,
        start: Instant,
    ) {
        var current = processed
        while (current < igns.size) {
            if (stopSignal.isStopRequested(PipelinePhase.OCID_LOOKUP)) {
                throw PhaseStoppedException(PipelinePhase.OCID_LOOKUP)
            }
            val permits = SchedulerPhaseUtils.acquirePermits(rateLimiter, batchSize, igns.size - current)
            if (permits == 0) {
                yield()
                continue
            }

            val chunk = igns.subList(current, current + permits)
            coroutineScope {
                chunk.map { ign ->
                    async(Dispatchers.IO) {
                        fetchAndCollectOcidAsync(ign, workerExecutor, successCount, failCount, resultsSink)
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
     * Each successful mapping is sent to [resultsSink] for streaming write — no
     * in-memory accumulation.
     */
    private suspend fun fetchAndCollectOcidAsync(
        ign: String,
        workerExecutor: ExecutorService,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
        resultsSink: SendChannel<String>,
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
                resultsSink.send(json)
                successCount.incrementAndGet()
            }
        } catch (ex: Exception) {
            failCount.incrementAndGet()
        }
    }
}
