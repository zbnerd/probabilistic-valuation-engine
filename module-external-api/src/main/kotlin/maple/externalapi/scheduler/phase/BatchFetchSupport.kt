package maple.externalapi.scheduler.phase

import io.github.bucket4j.Bucket
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.PhaseStoppedException
import maple.externalapi.snapshot.ChunkedSnapshotSink
import maple.externalapi.snapshot.SnapshotChunkRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Emit a progress log every N items processed. 5,000 keeps log volume bounded for large fetches. */
private const val PROGRESS_LOG_INTERVAL: Int = 5_000

/** Fetch latency (ms) above which a single fetch is treated as slow and logged. */
private const val SLOW_FETCH_LATENCY_MS: Long = 500L

/** Sink submit latency (ms) above which a single snapshot enqueue is treated as slow and logged. */
private const val SLOW_SUBMIT_LATENCY_MS: Long = 100L

/** Per-OCID fetch timeout. */
private const val SINGLE_FETCH_TIMEOUT_MS: Long = 10_000L

/** Batch wait latency (ms) above which a batch is logged. */
private const val SLOW_BATCH_WAIT_MS: Long = 1_000L

/** Endpoint-scoped fetch context shared by processBatch / fetchSingle / handleFailure. */
data class BatchFetchContext(
    val endpoint: String,
    val phase: PipelinePhase = PipelinePhase.IDLE,
    val apiEndpoint: ExternalApiEndpoint,
    val onFetched: () -> Unit,
    val onFailed: () -> Unit,
)

/**
 * Shared rate-limit + batch-processing utilities for endpoint-specific fetch phases.
 * Encapsulates the bucket4j integration, the recursive processBatch loop, and the
 * common per-OCID fetch / failure handling. Both CharacterBasicFetchPhase and
 * ItemEquipmentFetchPhase delegate to this support instead of duplicating logic.
 */
@Component
class BatchFetchSupport(
    private val clientPort: ExternalApiClientPort,
    private val fetchMetrics: SnapshotFetchMetrics,
    @Value("\${external-api.concurrency.max-in-flight:100}")
    maxInFlight: Int,
    private val schedulerRateLimiter: SchedulerRateLimiter,
    private val schedulerProgressLogger: SchedulerProgressLogger,
    private val httpStatusExtractor: HttpStatusExtractor,
    private val stopSignal: PhaseStopSignal,
) {
    private val log = LoggerFactory.getLogger(BatchFetchSupport::class.java)
    private val semaphore = Semaphore(maxInFlight)

    /** Construct a token-bucket rate limiter sized to `permitsPerSecond`. */
    fun newRateLimiter(permitsPerSecond: Int): Bucket = schedulerRateLimiter.newRateLimiter(permitsPerSecond)

    /**
     * Process a batch of (key, ocid) entries with rate-limit gated concurrency.
     * Returns (successCount, failCount).
     */
    suspend fun processBatch(
        rateLimiter: Bucket,
        entries: List<Map.Entry<String, String>>,
        batchSize: Int,
        ctx: BatchFetchContext,
        sink: ChunkedSnapshotSink,
        runId: String,
        start: Instant,
    ): Pair<Int, Int> {
        var processed = 0
        var progress = BatchProgress(start = start)

        while (processed < entries.size) {
            if (stopSignal.isStopRequested(ctx.phase)) {
                throw PhaseStoppedException(ctx.phase)
            }
            val permits = schedulerRateLimiter.acquirePermitsSuspend(rateLimiter, batchSize, entries.size - processed)
            if (permits == 0) continue

            val chunk = entries.subList(processed, processed + permits)
            val batchWaitStart = Instant.now()

            val batchResults = coroutineScope {
                chunk.map { (_, ocid) ->
                    async {
                        runCatching {
                            fetchSingle(ocid, ctx, sink)
                        }.onFailure { ex ->
                            handleFailure(ocid, ctx, sink, ex)
                        }.getOrNull()
                    }
                }.awaitAll()
            }

            val batchWaitDuration = Duration.between(batchWaitStart, Instant.now())
            fetchMetrics.recordBatchWait(ctx.endpoint, batchWaitDuration, chunk.size)
            if (batchWaitDuration.toMillis() >= SLOW_BATCH_WAIT_MS) {
                log.info(
                    "[SnapshotFetchMetrics] batch wait: endpoint={}, runId={}, batchSize={}, durationMs={}, success={}, failed={}",
                    ctx.endpoint,
                    runId,
                    chunk.size,
                    batchWaitDuration.toMillis(),
                    progress.successCount,
                    progress.failCount,
                )
            }

            val batchSuccess = batchResults.filterNotNull().size
            progress = progress
                .addSuccess(batchSuccess)
                .addFailure(chunk.size - batchSuccess)
            processed += permits

            if (progress.shouldLogProgress(PROGRESS_LOG_INTERVAL)) {
                progress = progress.markLogged()
                schedulerProgressLogger.logProgress(ctx.endpoint, progress.totalProcessed(), entries.size, progress.successCount, progress.failCount, progress.start)
            }
        }
        return progress.successCount to progress.failCount
    }

    private suspend fun fetchSingle(ocid: String, ctx: BatchFetchContext, sink: ChunkedSnapshotSink): Boolean = withTimeoutOrNull(SINGLE_FETCH_TIMEOUT_MS) {
        semaphore.withPermit {
            val fetchStart = Instant.now()
            val bodyBytes = clientPort.fetch(
                ExternalApiProvider.NEXON,
                ctx.apiEndpoint,
                ocid,
            ).await()

            val fetchDuration = Duration.between(fetchStart, Instant.now())
            fetchMetrics.recordFetchJoin(ctx.endpoint, fetchDuration)

            val queueDepthBeforeSubmit = sink.queueDepth()
            val submitStart = Instant.now()
            sink.submit(
                SnapshotChunkRecord.Success(
                    key = ocid,
                    endpoint = ctx.endpoint,
                    keyType = "OCID",
                    httpStatus = 200,
                    fetchedAt = Instant.now(),
                    bodyBytes = bodyBytes,
                ),
            )
            val submitDuration = Duration.between(submitStart, Instant.now())
            fetchMetrics.recordSinkSubmit(ctx.endpoint, submitDuration, queueDepthBeforeSubmit)
            if (fetchDuration.toMillis() >= SLOW_FETCH_LATENCY_MS || submitDuration.toMillis() >= SLOW_SUBMIT_LATENCY_MS) {
                log.info(
                    "[SnapshotFetchMetrics] fetch/sink: endpoint={}, ocid={}, responseBytes={}, fetchJoinMs={}, sinkSubmitMs={}, sinkQueueDepthBeforeSubmit={}",
                    ctx.endpoint,
                    ocid,
                    bodyBytes.size,
                    fetchDuration.toMillis(),
                    submitDuration.toMillis(),
                    queueDepthBeforeSubmit,
                )
            }
            ctx.onFetched()
            true
        }
    } ?: false

    private fun handleFailure(ocid: String, ctx: BatchFetchContext, sink: ChunkedSnapshotSink, ex: Throwable) {
        val httpStatus = httpStatusExtractor.extract(ex)
        sink.submit(
            SnapshotChunkRecord.Failure(
                key = ocid,
                endpoint = ctx.endpoint,
                keyType = "OCID",
                httpStatus = httpStatus,
                fetchedAt = Instant.now(),
                errorMessage = ex.message ?: "unknown",
            ),
        )
        ctx.onFailed()
    }
}
