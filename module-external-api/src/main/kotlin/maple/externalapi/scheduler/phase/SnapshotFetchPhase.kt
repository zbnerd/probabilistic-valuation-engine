package maple.externalapi.scheduler.phase

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
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
    private val executor: LogicExecutor,
    @Qualifier("characterBasicSnapshotPublisher")
    private val characterBasicPublisher: SnapshotChunkEventPublisher,
    private val itemEquipmentPublisher: SnapshotChunkEventPublisher,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:/data/external-api}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(SnapshotFetchPhase::class.java)

    fun executeCharacterBasic(executor: ExecutorService, ocidCache: Map<String, String>) {
        execute(
            executor,
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
    }

    fun executeItemEquipment(executor: ExecutorService, entries: List<Map.Entry<String, String>>) {
        execute(
            executor,
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
    }

    private fun execute(
        executor: ExecutorService,
        entries: List<Map.Entry<String, String>>,
        config: SnapshotFetchConfig,
    ) {
        if (config.skipIfExisting) {
            val existing = artifactStore.listStoredKeys(config.apiEndpoint)
            if (existing.isNotEmpty()) {
                log.info("[Scheduler] {} already done ({} files), skipping", config.endpoint, existing.size)
                return
            }
        }

        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping {}", config.endpoint)
            return
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
        var processed = 0
        var lastProgressLog = 0

        try {
            while (processed < entries.size) {
                val permits = SchedulerPhaseUtils.acquirePermits(rateLimiter, batchSize, entries.size - processed)
                if (permits == 0) continue

                val chunk = entries.subList(processed, processed + permits)
                processed += permits

                val batchWaitStart = Instant.now()
                val futures = chunk.map { (ign, ocid) ->
                    executor.submit(Callable { fetchSingle(ocid, config, sink, successCount, failCount) })
                }

                futures.forEach { it.get() }
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
                if (progress - lastProgressLog >= 5000) {
                    lastProgressLog = progress
                    SchedulerPhaseUtils.logProgress(config.endpoint, progress, entries.size, successCount.get(), failCount.get(), start)
                }
            }
        } finally {
            sink.close()
        }

        config.recordDuration(Duration.between(start, Instant.now()))
        SchedulerPhaseUtils.logSummary(config.endpoint, entries.size, successCount.get(), successCount.get(), failCount.get(), start)
    }

    private fun fetchSingle(
        ocid: String,
        config: SnapshotFetchConfig,
        sink: ChunkedSnapshotSink,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
    ) {
        executor.executeWithFallback(
            { performSnapshotFetch(ocid, config, sink, successCount) },
            { ex -> handleSnapshotFailure(ocid, config, sink, failCount, ex) },
            TaskContext.of("SnapshotFetch", "FetchSingle", ocid),
        )
    }

    private fun performSnapshotFetch(
        ocid: String,
        config: SnapshotFetchConfig,
        sink: ChunkedSnapshotSink,
        successCount: AtomicInteger,
    ) {
        val fetchStart = Instant.now()
        val bodyBytes = clientPort.fetch(
            ExternalApiProvider.NEXON,
            config.apiEndpoint,
            ocid,
        ).join()
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
