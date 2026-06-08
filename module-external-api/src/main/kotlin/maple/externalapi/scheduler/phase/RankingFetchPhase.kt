package maple.externalapi.scheduler.phase

import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.domain.KeyType
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.parser.RankingEntryParser
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.ChunkedSnapshotSink
import maple.externalapi.snapshot.EndpointSinkFactory
import maple.externalapi.snapshot.SnapshotChunkRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** Emit a progress log every N items fetched. 10,000 chosen for ranking phase (lower call rate). */
private const val PROGRESS_LOG_INTERVAL: Int = 10_000

@Component
@ConditionalOnProperty(name = ["external-api.ranking.enabled"], havingValue = "true", matchIfMissing = false)
class RankingFetchPhase(
    private val clientPort: ExternalApiClientPort,
    private val rankingEntryParser: RankingEntryParser,
    private val metrics: ExternalApiMetrics,
    private val sinkFactory: EndpointSinkFactory,
    @Value("\${external-api.ranking.max-pages:300}")
    private val maxPages: Int,
    @Value("\${external-api.ranking.permits-per-second:50}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
    private val clock: Clock = Clock.systemUTC(),
    private val runIdGenerator: RunIdGenerator,
    private val runMarkerWriter: RunMarkerWriter,
    private val schedulerRateLimiter: SchedulerRateLimiter,
    private val schedulerProgressLogger: SchedulerProgressLogger,
    private val httpStatusExtractor: HttpStatusExtractor,
) {
    private val log = LoggerFactory.getLogger(RankingFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService): CompletableFuture<Path> {
        val runId = runIdGenerator.newRunId()
        val date = LocalDate.now(clock).minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val runDir: Path = Paths.get(storeBasePath, "runs", runId)

        runMarkerWriter.writeRunningMarker(runDir)

        val sink = sinkFactory.createForRanking(runDir)

        val rateLimiter = schedulerRateLimiter.newRateLimiter(permitsPerSecond)
        val start = Instant.now(clock)
        val dispatcher = workerExecutor.asCoroutineDispatcher()

        log.info("[RankingFetch] starting: runId={}, date={}, maxPages={}, permitsPerSecond={}", runId, date, maxPages, permitsPerSecond)

        return CoroutineScope(dispatcher).future {
            try {
                val (fetched, failed) = processPagesSuspend(sink, rateLimiter, date)
                schedulerProgressLogger.logSummary("RankingFetch", fetched, fetched, fetched, failed, start)
            } catch (ex: Throwable) {
                log.error("[RankingFetch] failed: runId={}, error={}", runId, ex.message)
                throw ex
            } finally {
                sink.close()
            }
            runDir
        }
    }

    /**
     * Sequential page processing with suspend-based rate limiting.
     * Replaces recursive CompletableFuture chain with while loop.
     */
    private suspend fun processPagesSuspend(
        sink: ChunkedSnapshotSink,
        rateLimiter: io.github.bucket4j.Bucket,
        date: String,
    ): Pair<Int, Int> {
        var fetched = 0
        var failed = 0
        var currentPage = 1

        while (currentPage <= maxPages) {
            val permits = schedulerRateLimiter.acquirePermitsSuspend(rateLimiter, 1, 1)
            if (permits == 0) continue // acquirePermitsSuspend already delays 100ms

            val requestKey = "$date:$currentPage"
            try {
                val bodyBytes = clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, requestKey).await()
                val count = submitRankingEntries(sink, bodyBytes, currentPage)
                fetched += count
                metrics.recordRankingFetched(count)
                if (fetched % PROGRESS_LOG_INTERVAL == 0) {
                    log.info("[RankingFetch] progress: fetched={}, failed={}, page={}/{}", fetched, failed, currentPage, maxPages)
                }
            } catch (ex: Throwable) {
                failed++
                metrics.recordRankingFailed()
                val status = httpStatusExtractor.extract(ex)
                sink.submit(
                    SnapshotChunkRecord.Failure(
                        key = requestKey,
                        endpoint = "ranking-overall",
                        keyType = KeyType.DATE_PAGE.name,
                        httpStatus = status,
                        fetchedAt = Instant.now(clock),
                        errorMessage = ex.message ?: "unknown",
                    ),
                )
                log.warn("[RankingFetch] page failed: page={}, status={}, error={}", currentPage, status, ex.message)
            }
            currentPage++
        }
        return fetched to failed
    }

    private fun submitRankingEntries(
        sink: ChunkedSnapshotSink,
        bodyBytes: ByteArray,
        page: Int,
    ): Int {
        val entries = rankingEntryParser.parseEntries(bodyBytes)
        if (entries.isEmpty()) {
            log.warn("[RankingFetch] no ranking array in response: page={}", page)
            return 0
        }

        for (entry in entries) {
            sink.submit(
                SnapshotChunkRecord.Success(
                    bodyBytes = entry.bodyBytes,
                    key = entry.characterName,
                    endpoint = "ranking-overall",
                    keyType = KeyType.DATE_PAGE.name,
                    httpStatus = 200,
                    fetchedAt = Instant.now(clock),
                ),
            )
        }
        return entries.size
    }
}
