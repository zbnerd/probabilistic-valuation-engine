package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.snapshot.ChunkedSnapshotSink
import maple.externalapi.snapshot.SinkEventPublisher
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.SnapshotSinkEventPublisher
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.slf4j.LoggerFactory
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
 * ITEM_EQUIPMENT snapshot fetch phase. Driven by ExternalApiScheduler's
 * continuous loop. No skipIfExisting guard — each cycle is expected to write
 * a fresh snapshot run.
 */
@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class ItemEquipmentFetchPhase(
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val volumeMetrics: SnapshotVolumeMetrics,
    private val metrics: ExternalApiMetrics,
    private val fetchMetrics: SnapshotFetchMetrics,
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
    private val log = LoggerFactory.getLogger(ItemEquipmentFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService, entries: List<Map.Entry<String, String>>): CompletableFuture<Unit> {
        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping item-equipment")
            return CompletableFuture.completedFuture(Unit)
        }

        val runId = SchedulerPhaseUtils.newRunId()
        val chunkConfig = chunkingProperties.configFor("item-equipment")
        val runDir = Paths.get(storeBasePath, "runs", runId)
        SchedulerPhaseUtils.writeRunningMarker(runDir)
        val sink = ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = "item-equipment",
            maxRecords = chunkConfig.maxRecords,
            maxUncompressedBytes = chunkConfig.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = SnapshotSinkEventPublisher(
                eventPublisher = SinkEventPublisher(eventPublisher),
                volumeMetrics = volumeMetrics,
                clock = clock,
            ),
            clock = clock,
        )

        val rateLimiter = batchSupport.newRateLimiter(permitsPerSecond)

        log.info("[Scheduler] ========== item-equipment lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, chunk={}records/{}bytes, runId={}",
            entries.size, permitsPerSecond, batchSize,
            chunkConfig.maxRecords, chunkConfig.maxUncompressedBytes, runId,
        )

        val start = Instant.now(clock)
        val ctx = BatchFetchContext(
            endpoint = "item-equipment",
            apiEndpoint = ExternalApiEndpoint.ITEM_EQUIPMENT,
            onFetched = { metrics.recordItemEquipmentFetched() },
            onFailed = { metrics.recordItemEquipmentFailed() },
        )

        val dispatcher = workerExecutor.asCoroutineDispatcher()
        return CoroutineScope(dispatcher).future {
            try {
                val (successCount, failCount) = batchSupport.processBatch(
                    rateLimiter, entries, batchSize, ctx, sink, runId, start,
                )
                SchedulerPhaseUtils.logSummary("item-equipment", entries.size, successCount, successCount, failCount, start)
            } finally {
                sink.close()
                metrics.itemEquipmentTimer().record(Duration.between(start, Instant.now(clock)))
            }
        }
    }
}
