package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.snapshot.ChunkedSnapshotSink
import maple.externalapi.snapshot.SinkEventPublisher
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/**
 * CHARACTER_BASIC snapshot fetch phase. Skips when stored keys already exist
 * (idempotent re-run safety — daily refresh should not double-fetch if a prior
 * run wrote chunks).
 */
@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class CharacterBasicFetchPhase(
    private val artifactStore: ExternalApiArtifactStorePort,
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val volumeMetrics: SnapshotVolumeMetrics,
    private val metrics: ExternalApiMetrics,
    private val fetchMetrics: SnapshotFetchMetrics,
    @Qualifier("characterBasicSnapshotPublisher")
    private val eventPublisher: SnapshotChunkEventPublisher,
    private val batchSupport: BatchFetchSupport,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(CharacterBasicFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService, ocidCache: Map<String, String>): CompletableFuture<Unit> {
        val existing = artifactStore.listStoredKeys(ExternalApiEndpoint.CHARACTER_BASIC)
        if (existing.isNotEmpty()) {
            log.info("[Scheduler] character-basic already done ({} files), skipping", existing.size)
            return CompletableFuture.completedFuture(Unit)
        }

        val entries = ocidCache.entries.toList()
        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping character-basic")
            return CompletableFuture.completedFuture(Unit)
        }

        val runId = SchedulerPhaseUtils.newRunId()
        val chunkConfig = chunkingProperties.configFor("character-basic")
        val runDir = Paths.get(storeBasePath, "runs", runId)
        SchedulerPhaseUtils.writeRunningMarker(runDir)
        val sink = ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = "character-basic",
            maxRecords = chunkConfig.maxRecords,
            maxUncompressedBytes = chunkConfig.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = SinkEventPublisher(eventPublisher),
            volumeMetrics = volumeMetrics,
            clock = clock,
        )

        val rateLimiter = batchSupport.newRateLimiter(permitsPerSecond)

        log.info("[Scheduler] ========== character-basic lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, chunk={}records/{}bytes, runId={}",
            entries.size, permitsPerSecond, batchSize,
            chunkConfig.maxRecords, chunkConfig.maxUncompressedBytes, runId,
        )

        val start = Instant.now(clock)
        val ctx = BatchFetchContext(
            endpoint = "character-basic",
            apiEndpoint = ExternalApiEndpoint.CHARACTER_BASIC,
            onFetched = { metrics.recordCharacterBasicFetched() },
            onFailed = { metrics.recordCharacterBasicFailed() },
        )

        val dispatcher = workerExecutor.asCoroutineDispatcher()
        return CoroutineScope(dispatcher).future {
            try {
                val (successCount, failCount) = batchSupport.processBatch(
                    rateLimiter, entries, batchSize, ctx, sink, runId, start,
                )
                SchedulerPhaseUtils.logSummary("character-basic", entries.size, successCount, successCount, failCount, start)
            } finally {
                sink.close()
                metrics.characterBasicTimer().record(Duration.between(start, Instant.now(clock)))
            }
        }
    }
}
