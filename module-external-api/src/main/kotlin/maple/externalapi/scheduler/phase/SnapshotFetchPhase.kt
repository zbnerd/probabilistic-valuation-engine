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

    fun executeCharacterBasic(workerExecutor: ExecutorService, ocidCache: Map<String, String>): CompletableFuture<Unit> =
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

    fun executeItemEquipment(workerExecutor: ExecutorService, entries: List<Map.Entry<String, String>>): CompletableFuture<Unit> =
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
    ): CompletableFuture<Unit> {
        if (config.skipIfExisting) {
            val existing = artifactStore.listStoredKeys(config.apiEndpoint)
            if (existing.isNotEmpty()) {
                log.info("[Scheduler] {} already done ({} files), skipping", config.endpoint, existing.size)
                return CompletableFuture.completedFuture(Unit)
            }
        }

        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping {}", config.endpoint)
            return CompletableFuture.completedFuture(Unit)
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
        val dispatcher = workerExecutor.asCoroutineDispatcher()

        return CoroutineScope(dispatcher).future {
            try {
                val (successCount, failCount) = processBatchSuspend(rateLimiter, entries, config, sink, runId, start)
                SchedulerPhaseUtils.logSummary(config.endpoint, entries.size, successCount, successCount, failCount, start)
            } finally {
                sink.close()
                config.recordDuration(Duration.between(start, Instant.now()))
            }
        }
    }

    /**
     * Batch processing with coroutine-based parallelism and semaphore-gated concurrency.
     * Replaces recursive CF chain + AtomicInteger with while loop + local accumulators.
     */
    private suspend fun processBatchSuspend(
        rateLimiter: io.github.bucket4j.Bucket,
        entries: List<Map.Entry<String, String>>,
        config: SnapshotFetchConfig,
        sink: ChunkedSnapshotSink,
        runId: String,
        start: Instant,
    ): Pair<Int, Int> {
        var processed = 0
        var successCount = 0
        var failCount = 0
        var lastProgressLog = 0

        while (processed < entries.size) {
            val permits = SchedulerPhaseUtils.acquirePermitsSuspend(rateLimiter, batchSize, entries.size - processed)
            if (permits == 0) continue // acquirePermitsSuspend already delays 100ms

            val chunk = entries.subList(processed, processed + permits)
            val batchWaitStart = Instant.now()

            val batchResults = coroutineScope {
                chunk.map { (_, ocid) ->
                    async {
                        runCatching {
                            fetchSingle(ocid, config, sink)
                        }.onFailure { ex ->
                            handleSnapshotFailure(ocid, config, sink, ex)
                        }.getOrNull()
                    }
                }.awaitAll()
            }

            val batchWaitDuration = Duration.between(batchWaitStart, Instant.now())
            fetchMetrics.recordBatchWait(config.endpoint, batchWaitDuration, chunk.size)
            if (batchWaitDuration.toMillis() >= 1_000) {
                log.info(
                    "[SnapshotFetchMetrics] batch wait: endpoint={}, runId={}, batchSize={}, durationMs={}, success={}, failed={}",
                    config.endpoint,
                    runId,
                    chunk.size,
                    batchWaitDuration.toMillis(),
                    successCount,
                    failCount,
                )
            }

            val batchSuccess = batchResults.filterNotNull().size
            successCount += batchSuccess
            failCount += chunk.size - batchSuccess

            processed += permits

            val progress = successCount + failCount
            if (progress - lastProgressLog >= 5000) {
                lastProgressLog = progress
                SchedulerPhaseUtils.logProgress(config.endpoint, progress, entries.size, successCount, failCount, start)
            }
        }
        return successCount to failCount
    }

    /**
     * Fetches a single OCID with semaphore-gated concurrency and 10s timeout.
     * Returns true on success, null on failure (for runCatching).
     */
    private suspend fun fetchSingle(
        ocid: String,
        config: SnapshotFetchConfig,
        sink: ChunkedSnapshotSink,
    ): Boolean {
        return withTimeoutOrNull(10_000L) {
            semaphore.withPermit {
                val fetchStart = Instant.now()
                val bodyBytes = clientPort.fetch(
                    ExternalApiProvider.NEXON,
                    config.apiEndpoint,
                    ocid,
                ).await()

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
                config.onFetched()
                true
            }
        } ?: false
    }

    private fun handleSnapshotFailure(
        ocid: String,
        config: SnapshotFetchConfig,
        sink: ChunkedSnapshotSink,
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
        config.onFailed()
    }
}
