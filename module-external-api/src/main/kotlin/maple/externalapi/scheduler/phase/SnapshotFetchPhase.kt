package maple.externalapi.scheduler.phase

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.ChunkedSnapshotSink
import maple.externalapi.snapshot.SnapshotChunkRecord
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

data class SnapshotFetchConfig(
    val endpoint: String,
    val apiEndpoint: ExternalApiEndpoint,
    val eventPublisher: SnapshotChunkEventPublisher,
    val onFetched: () -> Unit,
    val onFailed: () -> Unit,
    val recordDuration: (Duration) -> Unit,
    val skipIfExisting: Boolean = false,
)

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class SnapshotFetchPhase(
    private val clientPort: ExternalApiClientPort,
    private val artifactStore: ExternalApiArtifactStorePort,
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val volumeMetrics: SnapshotVolumeMetrics,
    private val metrics: ExternalApiMetrics,
    private val fetchMetrics: SnapshotFetchMetrics,
    @Qualifier("characterBasicSnapshotPublisher")
    private val characterBasicPublisher: SnapshotChunkEventPublisher,
    private val itemEquipmentPublisher: SnapshotChunkEventPublisher,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
    @Value("\${external-api.concurrency.max-in-flight:100}")
    maxInFlight: Int,
) {
    private val log = LoggerFactory.getLogger(SnapshotFetchPhase::class.java)
    private val semaphore = Semaphore(maxInFlight)

    fun executeCharacterBasic(workerExecutor: ExecutorService, ocidCache: Map<String, String>): CompletableFuture<Void> =
        execute(
            workerExecutor,
            ocidCache.entries.toList(),
            SnapshotFetchConfig(
                endpoint = "character-basic",
                apiEndpoint = ExternalApiEndpoint.CHARACTER_BASIC,
                eventPublisher = characterBasicPublisher,
                onFetched = { metrics.recordCharacterBasicFetched() },
                onFailed = { metrics.recordCharacterBasicFailed() },
                recordDuration = { metrics.characterBasicTimer().record(it) },
                skipIfExisting = true,
            ),
        )

    fun executeItemEquipment(workerExecutor: ExecutorService, entries: List<Map.Entry<String, String>>): CompletableFuture<Void> =
        execute(
            workerExecutor,
            entries,
            SnapshotFetchConfig(
                endpoint = "item-equipment",
                apiEndpoint = ExternalApiEndpoint.ITEM_EQUIPMENT,
                eventPublisher = itemEquipmentPublisher,
                onFetched = { metrics.recordItemEquipmentFetched() },
                onFailed = { metrics.recordItemEquipmentFailed() },
                recordDuration = { metrics.itemEquipmentTimer().record(it) },
            ),
        )

    private fun execute(
        workerExecutor: ExecutorService,
        entries: List<Map.Entry<String, String>>,
        config: SnapshotFetchConfig,
    ): CompletableFuture<Void> {
        if (config.skipIfExisting) {
            val existing = artifactStore.listStoredKeys(config.apiEndpoint)
            if (existing.isNotEmpty()) {
                log.info("[Scheduler] {} already done ({} files), skipping", config.endpoint, existing.size)
                return CompletableFuture.completedFuture(null)
            }
        }

        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping {}", config.endpoint)
            return CompletableFuture.completedFuture(null)
        }

        val runId = SchedulerPhaseUtils.newRunId()
        val chunkConfig = chunkingProperties.configFor(config.endpoint)
        val runDir = Paths.get(storeBasePath, "runs", runId)
        SchedulerPhaseUtils.writeRunningMarker(runDir)
        val sink = ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = config.endpoint,
            maxRecords = chunkConfig.maxRecords,
            maxUncompressedBytes = chunkConfig.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = config.eventPublisher,
            volumeMetrics = volumeMetrics,
        )

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(permitsPerSecond)

        log.info("[Scheduler] ========== {} lookup start ==========", config.endpoint)
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, chunk={}records/{}bytes, runId={}",
            entries.size, permitsPerSecond, batchSize,
            chunkConfig.maxRecords, chunkConfig.maxUncompressedBytes, runId,
        )

        val start = Instant.now()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val lastProgressLog = AtomicInteger(0)

        return processBatch(
            workerExecutor = workerExecutor,
            rateLimiter = rateLimiter,
            entries = entries,
            processed = 0,
            config = config,
            sink = sink,
            runId = runId,
            successCount = successCount,
            failCount = failCount,
            lastProgressLog = lastProgressLog,
            start = start,
        ).whenComplete { _, _ ->
            sink.close()
            config.recordDuration(Duration.between(start, Instant.now()))
            SchedulerPhaseUtils.logSummary(config.endpoint, entries.size, successCount.get(), successCount.get(), failCount.get(), start)
        }
    }

    private fun processBatch(
        workerExecutor: ExecutorService,
        rateLimiter: io.github.bucket4j.Bucket,
        entries: List<Map.Entry<String, String>>,
        processed: Int,
        config: SnapshotFetchConfig,
        sink: ChunkedSnapshotSink,
        runId: String,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
        lastProgressLog: AtomicInteger,
        start: Instant,
    ): CompletableFuture<Void> {
        if (processed >= entries.size) {
            return CompletableFuture.completedFuture(null)
        }

        val permits = SchedulerPhaseUtils.acquirePermits(rateLimiter, batchSize, entries.size - processed)
        if (permits == 0) {
            return processBatch(workerExecutor, rateLimiter, entries, processed, config, sink, runId, successCount, failCount, lastProgressLog, start)
        }

        val chunk = entries.subList(processed, processed + permits)
        val batchWaitStart = Instant.now()
        val futures = chunk.map { (_, ocid) ->
            fetchSingleAsync(ocid, config, sink, workerExecutor, successCount, failCount)
        }

        return CompletableFuture.allOf(*futures.toTypedArray()).thenCompose {
            val batchWaitDuration = Duration.between(batchWaitStart, Instant.now())
            fetchMetrics.recordBatchWait(config.endpoint, batchWaitDuration, chunk.size)
            if (batchWaitDuration.toMillis() >= 1_000) {
                log.info(
                    "[SnapshotFetchMetrics] batch wait: endpoint={}, runId={}, batchSize={}, durationMs={}, success={}, failed={}",
                    config.endpoint,
                    runId,
                    chunk.size,
                    batchWaitDuration.toMillis(),
                    successCount.get(),
                    failCount.get(),
                )
            }

            val progress = successCount.get() + failCount.get()
            if (progress - lastProgressLog.get() >= 5000) {
                lastProgressLog.set(progress)
                SchedulerPhaseUtils.logProgress(config.endpoint, progress, entries.size, successCount.get(), failCount.get(), start)
            }
            processBatch(workerExecutor, rateLimiter, entries, processed + permits, config, sink, runId, successCount, failCount, lastProgressLog, start)
        }
    }

    private fun fetchSingleAsync(
        ocid: String,
        config: SnapshotFetchConfig,
        sink: ChunkedSnapshotSink,
        workerExecutor: ExecutorService,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
    ): CompletableFuture<Void> {
        return tryAcquireWithBackoff(semaphore, workerExecutor)
            .thenCompose { acquired ->
                val fetchFuture: CompletableFuture<Void> = if (!acquired) {
                    log.warn("[{}] backpressure: semaphore exhausted, skipping ocid={}", config.endpoint, ocid.take(3) + "***")
                    failCount.incrementAndGet()
                    config.onFailed()
                    CompletableFuture.completedFuture(null)
                } else {
                    val fetchStart = Instant.now()
                    clientPort.fetch(
                        ExternalApiProvider.NEXON,
                        config.apiEndpoint,
                        ocid,
                    )
                        .thenAcceptAsync({ bodyBytes ->
                            handleSnapshotSuccess(ocid, config, sink, successCount, fetchStart, bodyBytes)
                        }, workerExecutor)
                        .handle { _, ex ->
                            if (ex != null) {
                                handleSnapshotFailure(ocid, config, sink, failCount, ex)
                            }
                            null
                        }
                }
                fetchFuture.whenComplete { _, _ -> if (acquired) semaphore.release() }
            }
    }

    private fun tryAcquireWithBackoff(semaphore: Semaphore, executor: ExecutorService): CompletableFuture<Boolean> {
        if (semaphore.tryAcquire()) return CompletableFuture.completedFuture(true)
        return CompletableFuture.supplyAsync({
            var retries = 0
            while (!semaphore.tryAcquire()) {
                if (retries++ >= 3) return@supplyAsync false
                Thread.sleep(50L * retries)
            }
            true
        }, executor)
    }

    private fun handleSnapshotSuccess(
        ocid: String,
        config: SnapshotFetchConfig,
        sink: ChunkedSnapshotSink,
        successCount: AtomicInteger,
        fetchStart: Instant,
        bodyBytes: ByteArray,
    ) {
        val fetchDuration = Duration.between(fetchStart, Instant.now())
        fetchMetrics.recordFetchJoin(config.endpoint, fetchDuration)

        val queueDepthBeforeSubmit = sink.queueDepth()
        val submitStart = Instant.now()
        sink.submit(
            SnapshotChunkRecord.Success(
                key = ocid,
                endpoint = config.endpoint,
                keyType = "OCID",
                httpStatus = 200,
                fetchedAt = Instant.now(),
                bodyBytes = bodyBytes,
            ),
        )
        val submitDuration = Duration.between(submitStart, Instant.now())
        fetchMetrics.recordSinkSubmit(config.endpoint, submitDuration, queueDepthBeforeSubmit)
        if (fetchDuration.toMillis() >= 500 || submitDuration.toMillis() >= 100) {
            log.info(
                "[SnapshotFetchMetrics] fetch/sink: endpoint={}, ocid={}, responseBytes={}, fetchJoinMs={}, sinkSubmitMs={}, sinkQueueDepthBeforeSubmit={}",
                config.endpoint,
                ocid,
                bodyBytes.size,
                fetchDuration.toMillis(),
                submitDuration.toMillis(),
                queueDepthBeforeSubmit,
            )
        }
        successCount.incrementAndGet()
        config.onFetched()
    }

    private fun handleSnapshotFailure(
        ocid: String,
        config: SnapshotFetchConfig,
        sink: ChunkedSnapshotSink,
        failCount: AtomicInteger,
        ex: Throwable,
    ) {
        val httpStatus = SchedulerPhaseUtils.extractHttpStatus(ex)
        sink.submit(
            SnapshotChunkRecord.Failure(
                key = ocid,
                endpoint = config.endpoint,
                keyType = "OCID",
                httpStatus = httpStatus,
                fetchedAt = Instant.now(),
                errorMessage = ex.message ?: "unknown",
            ),
        )
        failCount.incrementAndGet()
        config.onFailed()
    }
}
