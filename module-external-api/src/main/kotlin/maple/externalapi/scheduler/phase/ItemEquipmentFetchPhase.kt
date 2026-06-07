package maple.externalapi.scheduler.phase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.snapshot.EndpointSinkFactory
import maple.externalapi.snapshot.SnapshotChunkingProperties
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
    private val chunkingProperties: SnapshotChunkingProperties,
    private val metrics: ExternalApiMetrics,
    private val fetchMetrics: SnapshotFetchMetrics,
    private val batchSupport: BatchFetchSupport,
    private val sinkFactory: EndpointSinkFactory,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
    private val clock: Clock = Clock.systemUTC(),
    private val runIdGenerator: RunIdGenerator,
    private val runMarkerWriter: RunMarkerWriter,
    private val schedulerProgressLogger: SchedulerProgressLogger,
) {
    private val log = LoggerFactory.getLogger(ItemEquipmentFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService, entries: List<Map.Entry<String, String>>): CompletableFuture<Unit> {
        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping item-equipment")
            return CompletableFuture.completedFuture(Unit)
        }

        val runId = runIdGenerator.newRunId()
        val chunkConfig = chunkingProperties.configFor("item-equipment")
        val runDir = Paths.get(storeBasePath, "runs", runId)
        runMarkerWriter.writeRunningMarker(runDir)
        val sink = sinkFactory.createForItemEquipment(runDir)

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
                schedulerProgressLogger.logSummary("item-equipment", entries.size, successCount, successCount, failCount, start)
            } finally {
                sink.close()
                metrics.itemEquipmentTimer().record(Duration.between(start, Instant.now(clock)))
            }
        }
    }
}
