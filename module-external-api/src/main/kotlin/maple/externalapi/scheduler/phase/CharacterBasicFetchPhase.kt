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
 * CHARACTER_BASIC snapshot fetch phase. Skips when stored keys already exist
 * (idempotent re-run safety — daily refresh should not double-fetch if a prior
 * run wrote chunks).
 */
@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class CharacterBasicFetchPhase(
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
    private val log = LoggerFactory.getLogger(CharacterBasicFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService, ocidCache: Map<String, String>, runId: String? = null): CompletableFuture<Unit> {
        val existing = objectStorage.listByPrefix("character-basic/")
        if (existing.isNotEmpty()) {
            log.info("[Scheduler] character-basic already done ({} files), skipping", existing.size)
            return CompletableFuture.completedFuture(Unit)
        }

        val entries = ocidCache.entries.toList()
        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping character-basic")
            return CompletableFuture.completedFuture(Unit)
        }

        val effectiveRunId = runId ?: runIdGenerator.newRunId()
        val chunkConfig = chunkingProperties.configFor("character-basic")
        return runLifecycle.startEndpoint(effectiveRunId, CHARACTER_BASIC_ENDPOINT).thenCompose {
            val sink = sinkFactory.createForCharacterBasic(effectiveRunId)
            val rateLimiter = batchSupport.newRateLimiter(permitsPerSecond)

            log.info("[Scheduler] ========== character-basic lookup start ==========")
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
                endpoint = CHARACTER_BASIC_ENDPOINT,
                phase = PipelinePhase.CHARACTER_BASIC,
                apiEndpoint = ExternalApiEndpoint.CHARACTER_BASIC,
                onFetched = { metrics.recordCharacterBasicFetched() },
                onFailed = { metrics.recordCharacterBasicFailed() },
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
                    CHARACTER_BASIC_ENDPOINT,
                    entries.size,
                    successCount,
                    successCount,
                    failCount,
                    start,
                )
            }.thenCompose {
                metrics.characterBasicTimer().record(Duration.between(start, Instant.now(clock)))
                sink.closeAsync().thenApply { Unit }
            }
        }
    }

    private companion object {
        const val CHARACTER_BASIC_ENDPOINT: String = "character-basic"
    }
}
