package maple.externalapi.scheduler.phase

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import maple.expectation.common.storage.ObjectStorage
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.snapshot.EndpointSinkFactory
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.pipeline.artifact.lifecycle.RunLifecycle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * ITEM_EQUIPMENT snapshot fetch phase. Driven by ExternalApiScheduler's
 * continuous loop. No skipIfExisting guard — each cycle is expected to write
 * a fresh snapshot run.
 */
@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class ItemEquipmentFetchPhase(
    private val objectStorage: ObjectStorage,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val metrics: ExternalApiMetrics,
    private val fetchMetrics: SnapshotFetchMetrics,
    private val batchSupport: BatchFetchSupport,
    private val sinkFactory: EndpointSinkFactory,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    private val clock: Clock = Clock.systemUTC(),
    private val runIdGenerator: RunIdGenerator,
    private val runLifecycle: RunLifecycle,
    private val schedulerProgressLogger: SchedulerProgressLogger,
) {
    private val log = LoggerFactory.getLogger(ItemEquipmentFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService, entries: List<Map.Entry<String, String>>, runId: String? = null): CompletableFuture<Unit> {
        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping item-equipment")
            return CompletableFuture.completedFuture(Unit)
        }

        val effectiveRunId = runId ?: runIdGenerator.newRunId()
        val chunkConfig = chunkingProperties.configFor("item-equipment")
        return runLifecycle.startEndpoint(effectiveRunId, ITEM_EQUIPMENT_ENDPOINT).thenCompose {
            val sink = sinkFactory.createForItemEquipment(effectiveRunId)
            val rateLimiter = batchSupport.newRateLimiter(permitsPerSecond)

            log.info("[Scheduler] ========== item-equipment lookup start ==========")
            log.info(
                "[Scheduler] config: total={}, rate={}/s, batchSize={}, chunk={}records/{}bytes, runId={}",
                entries.size,
                permitsPerSecond,
                batchSize,
                chunkConfig.maxRecords,
                chunkConfig.maxUncompressedBytes,
                effectiveRunId,
            )

            val start = Instant.now(clock)
            val ctx = BatchFetchContext(
                endpoint = ITEM_EQUIPMENT_ENDPOINT,
                phase = PipelinePhase.ITEM_EQUIPMENT,
                apiEndpoint = ExternalApiEndpoint.ITEM_EQUIPMENT,
                onFetched = { metrics.recordItemEquipmentFetched() },
                onFailed = { metrics.recordItemEquipmentFailed() },
            )

            val dispatcher = workerExecutor.asCoroutineDispatcher()
            CoroutineScope(dispatcher).future {
                val (successCount, failCount) = batchSupport.processBatch(
                    rateLimiter,
                    entries,
                    batchSize,
                    ctx,
                    sink,
                    effectiveRunId,
                    start,
                )
                schedulerProgressLogger.logSummary(
                    ITEM_EQUIPMENT_ENDPOINT,
                    entries.size,
                    successCount,
                    successCount,
                    failCount,
                    start,
                )
            }.thenCompose {
                metrics.itemEquipmentTimer().record(Duration.between(start, Instant.now(clock)))
                sink.closeAsync().thenApply { Unit }
            }
        }
    }

    private companion object {
        const val ITEM_EQUIPMENT_ENDPOINT: String = "item-equipment"
    }
}
