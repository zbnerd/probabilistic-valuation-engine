package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import maple.common.parser.StreamingChunkParser
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.storage.ObjectStorage
import maple.externalapi.artifact.OcidMappingArtifactWriter
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.metrics.ChunkParserMetrics
import maple.externalapi.poc.parquet.ParquetOcidMappingWriter
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.PhaseStoppedException
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import maple.pipeline.artifact.identity.OcidMappingArtifactLayout
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.write.ArtifactReceipt
import maple.pipeline.artifact.write.GzipArtifactSession
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * OCID lookup phase scheduler (Issue #1128).
 *
 * <p>CPU-bound 작업 (JSON parse/serialize, GZIP decompress) 을 `Dispatchers.Default` 로 offload.
 * `readCharacterNamesFromChunks()` + `processBatch()` + `fetchAndCollectOcidAsync()` 는 `suspend fun` 으로 refactor.
 * Caller (ExternalApiScheduler) 는 `runBlocking { ocidLookupPhase.execute(workerExecutor, runKey) }` (multi-threaded VT, short-lived).
 *
 * <p>Input chunks remain on [ObjectStorage]. Output mapping identity comes from
 * [OcidMappingArtifactLayout], while [OcidMappingArtifactWriter] owns gzip,
 * digest, upload, and cleanup lifetime. The current per-IGN lookup path goes
 * through [ExternalApiClientPort].
 *
 * <p>Each successful mapping is pushed to a [Channel] and consumed by one
 * writer coroutine. The previous implementation accumulated all 600K+
 * entries in a `MutableList<String>` until phase end. Streaming keeps the
 * heap footprint bounded while also teeing each line to the optional
 * best-effort Parquet sidecar.
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
    private val stopSignal: PhaseStopSignal,
    private val streamingChunkParser: StreamingChunkParser,
    private val chunkParserMetrics: ChunkParserMetrics,
    private val ocidMappingArtifactWriter: OcidMappingArtifactWriter,
) {
    private val log = LoggerFactory.getLogger(OcidLookupPhase::class.java)

    /**
     * External entry point. Caller (ExternalApiScheduler) uses:
     * `ocidLookupPhase.execute(workerExecutor, rankingRunId, runId)`
     */
    suspend fun execute(workerExecutor: ExecutorService, rankingRunId: String, runId: String) {
        deleteOldMappingFiles(runId)

        val rankingRoot = SourceArtifactLayout.runRoot(rankingRunId)
        val igns = readCharacterNamesFromChunks(rankingRunId)
        if (igns.isEmpty()) {
            log.warn("[Scheduler] no character names from chunks: {}", rankingRoot.value)
            return
        }
        log.info("[Scheduler] read {} character names from chunks: {}", igns.size, rankingRoot.value)

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(ocidLookupPermitsPerSecond)

        log.info("[Scheduler] ========== OCID lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, store=ObjectStorage (streaming)",
            igns.size,
            ocidLookupPermitsPerSecond,
            batchSize,
        )

        val start = Instant.now()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val lastProgressLog = AtomicInteger(0)
        val resultsChannel = Channel<String>(Channel.BUFFERED)
        val mappingReceipt = coroutineScope {
            val writer = async(Dispatchers.Default) { writeMapping(resultsChannel, runId) }
            processBatch(
                workerExecutor = workerExecutor,
                rateLimiter = rateLimiter,
                igns = igns,
                processed = 0,
                successCount = successCount,
                failCount = failCount,
                lastProgressLog = lastProgressLog,
                resultsSink = resultsChannel,
                start = start,
            )
            resultsChannel.close()
            writer.await()
        }
        log.info(
            "[Scheduler] streamed {} OCID mappings to {} (heap-bounded writer session)",
            successCount.get(),
            mappingReceipt.key.value,
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
                manifestPath = mappingReceipt.key.value,
                totalRecords = successCount.get(),
                totalFailed = failCount.get(),
                chunkCount = 1,
                startedAt = start,
                finishedAt = Instant.now(),
                createdAt = Instant.now(),
            ),
        )
    }

    private suspend fun writeMapping(
        resultsChannel: Channel<String>,
        runId: String,
    ): ArtifactReceipt {
        val session = ocidMappingArtifactWriter.open(runId)
        val parquetSidecar = openParquetSidecar(runId)
        return runCatching { drainMappingLines(resultsChannel, session, parquetSidecar) }
            .fold(
                onSuccess = { uncompressedBytes ->
                    completeMapping(session, parquetSidecar, uncompressedBytes, runId)
                },
                onFailure = { failure -> abortMapping(session, parquetSidecar, failure) },
            )
    }

    private suspend fun drainMappingLines(
        resultsChannel: Channel<String>,
        session: GzipArtifactSession,
        parquetSidecar: BestEffortParquetSidecar?,
    ): Long {
        var uncompressedBytes = 0L
        for (entry in resultsChannel) {
            val line = entry.toByteArray(Charsets.UTF_8)
            session.output.write(line)
            session.output.write('\n'.code)
            uncompressedBytes += line.size + 1L
            parquetSidecar?.write(entry)?.let { failure ->
                log.warn("[Scheduler] 1423 PoC Parquet tee failed (non-fatal)", failure)
            }
        }
        return uncompressedBytes
    }

    private suspend fun completeMapping(
        session: GzipArtifactSession,
        parquetSidecar: BestEffortParquetSidecar?,
        uncompressedBytes: Long,
        runId: String,
    ): ArtifactReceipt {
        val parquetArtifact = parquetSidecar?.complete()
        val receipt = runCatching { session.complete(uncompressedBytes).await() }
            .getOrElse { failure ->
                parquetArtifact?.delete(failure)
                throw failure
            }
        uploadParquetSidecar(parquetArtifact, runId)
        return receipt
    }

    private fun abortMapping(
        session: GzipArtifactSession,
        parquetSidecar: BestEffortParquetSidecar?,
        failure: Throwable,
    ): Nothing {
        session.abort(failure)
        parquetSidecar?.abort(failure)
        throw failure
    }

    private fun openParquetSidecar(runId: String): BestEffortParquetSidecar? {
        val directory = runCatching { Files.createTempDirectory("ocid-mapping-parquet-$runId-") }
            .getOrElse { failure ->
                log.warn("[Scheduler] 1423 PoC Parquet temp creation failed (non-fatal)", failure)
                return null
            }
        val file = directory.resolve("ocid-mapping.parquet")
        return runCatching {
            BestEffortParquetSidecar(directory, file, ParquetOcidMappingWriter(file.toFile()))
        }.onFailure { failure ->
            deleteParquetPaths(file, directory, failure)
            log.warn("[Scheduler] 1423 PoC Parquet writer creation failed (non-fatal)", failure)
        }.getOrNull()
    }

    private suspend fun uploadParquetSidecar(artifact: ParquetArtifact?, runId: String) {
        if (artifact == null) return
        val key = OcidMappingArtifactLayout.parquetSidecar(runId)
        val storageUpload = runCatching { objectStorage.putFileAsync(key.value, artifact.file) }
            .getOrElse { failure -> CompletableFuture.failedFuture(failure) }
        val lifetimeUpload = storageUpload.whenComplete { _, failure -> artifact.delete(failure) }
        val uploadObserver = lifetimeUpload.thenApply { result -> result }
        runCatching { uploadObserver.await() }
            .onSuccess { log.info("[Scheduler] 1423 PoC: wrote side-by-side Parquet to {}", key.value) }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                log.warn("[Scheduler] 1423 PoC Parquet side-by-side write failed (non-fatal)", failure)
            }
    }

    private fun deleteParquetPaths(file: Path, directory: Path, cause: Throwable) {
        sequenceOf(
            runCatching { Files.deleteIfExists(file) }.exceptionOrNull(),
            runCatching { Files.deleteIfExists(directory) }.exceptionOrNull(),
        ).filterNotNull()
            .filter { failure -> failure !== cause }
            .forEach(cause::addSuppressed)
    }

    private fun writeParquetLine(writer: ParquetOcidMappingWriter, line: String) {
        val node = objectMapper.readTree(line)
        val userIgn = node.path("userIgn").asText()
        val ocidNode = node.path("ocid")
        val ocid = if (ocidNode.isMissingNode || ocidNode.isNull) null else ocidNode.asText()
        if (userIgn.isNotBlank()) writer.write(userIgn, ocid)
    }

    private inner class BestEffortParquetSidecar(
        private val directory: Path,
        private val file: Path,
        private var writer: ParquetOcidMappingWriter?,
    ) {
        fun write(line: String): Throwable? {
            val activeWriter = writer ?: return null
            val failure = runCatching { writeParquetLine(activeWriter, line) }.exceptionOrNull()
            if (failure != null) {
                writer = null
                runCatching { activeWriter.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                deleteParquetPaths(file, directory, failure)
            }
            return failure
        }

        fun complete(): ParquetArtifact? {
            val activeWriter = writer ?: return null
            writer = null
            return runCatching {
                activeWriter.close()
                ParquetArtifact(file, directory)
            }.getOrElse { failure ->
                deleteParquetPaths(file, directory, failure)
                log.warn("[Scheduler] 1423 PoC Parquet close failed (non-fatal)", failure)
                null
            }
        }

        fun abort(cause: Throwable) {
            val activeWriter = writer
            writer = null
            if (activeWriter != null) {
                runCatching { activeWriter.close() }.exceptionOrNull()?.let(cause::addSuppressed)
            }
            deleteParquetPaths(file, directory, cause)
        }
    }

    private inner class ParquetArtifact(
        val file: Path,
        private val directory: Path,
    ) {
        fun delete(lifetimeFailure: Throwable? = null) {
            val cleanupFailures = sequenceOf(
                runCatching { Files.deleteIfExists(file) }.exceptionOrNull(),
                runCatching { Files.deleteIfExists(directory) }.exceptionOrNull(),
            ).filterNotNull().toList()
            if (cleanupFailures.isEmpty()) return
            if (lifetimeFailure != null) {
                cleanupFailures
                    .filter { failure -> failure !== lifetimeFailure }
                    .forEach(lifetimeFailure::addSuppressed)
                return
            }
            val primary = cleanupFailures.first()
            cleanupFailures.drop(1)
                .filter { failure -> failure !== primary }
                .forEach(primary::addSuppressed)
            throw primary
        }
    }

    /**
     * GZIP decompress + line-bounded JSONL parse. CPU-bound →
     * `Dispatchers.Default`. Uses [StreamingChunkParser] for
     * streaming parse; no manual readTree per line.
     */
    suspend fun readCharacterNamesFromChunks(rankingRunId: String): List<String> = withContext(Dispatchers.Default) {
        val prefix = SourceArtifactLayout.chunksRoot(rankingRunId, RANKING_ENDPOINT).value
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
            SourceArtifactLayout.runRoot(rankingRunId).value,
            names.size,
        )
        names.toList()
    }

    /**
     * Delete old objects under [OcidMappingArtifactLayout.mappingPrefix], but
     * preserve the current run's mapping file.
     *
     * Previously this called `deleteByPrefix("$mappingDir/")` which deleted ALL
     * objects including the file we were about to write, causing a race where
     * sibling processes would see an empty mapping directory mid-run. Now we
     * list-then-delete-per-key, skipping the key that ends with
     * `ocid-mapping-$currentRunId.jsonl.gz`.
     */
    private fun deleteOldMappingFiles(currentRunId: String) {
        val prefix = OcidMappingArtifactLayout.mappingPrefix.value
        val preserveKey = OcidMappingArtifactLayout.mapping(currentRunId).value
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
        log.info(
            "[Scheduler] deleted {} old OCID mapping objects in {} (preserved current runId={})",
            deleted,
            OcidMappingArtifactLayout.mappingPrefix.value,
            currentRunId,
        )
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
                    "OCID lookup",
                    progress,
                    igns.size,
                    successCount.get(),
                    failCount.get(),
                    start,
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
                        Charsets.UTF_8,
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

    private companion object {
        const val RANKING_ENDPOINT: String = "ranking-overall"
    }
}
