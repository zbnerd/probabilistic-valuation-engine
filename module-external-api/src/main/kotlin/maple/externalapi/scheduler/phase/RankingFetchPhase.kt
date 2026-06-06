package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.domain.KeyType
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.ChunkedSnapshotSink
import maple.externalapi.snapshot.SinkEventPublisher
import maple.externalapi.snapshot.SnapshotChunkRecord
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/** Emit a progress log every N items fetched. 10,000 chosen for ranking phase (lower call rate). */
private const val PROGRESS_LOG_INTERVAL: Int = 10_000

@Component
@ConditionalOnProperty(name = ["external-api.ranking.enabled"], havingValue = "true", matchIfMissing = false)
class RankingFetchPhase(
    private val clientPort: ExternalApiClientPort,
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val volumeMetrics: SnapshotVolumeMetrics,
    private val metrics: ExternalApiMetrics,
    @Qualifier("rankingSnapshotPublisher")
    private val rankingPublisher: SnapshotChunkEventPublisher,
    @Value("\${external-api.ranking.max-pages:300}")
    private val maxPages: Int,
    @Value("\${external-api.ranking.permits-per-second:50}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(RankingFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService): CompletableFuture<Path> {
        val runId = SchedulerPhaseUtils.newRunId()
        val date = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val runDir: Path = Paths.get(storeBasePath, "runs", runId)
        val endpointConfig = chunkingProperties.configFor("ranking-overall")

        SchedulerPhaseUtils.writeRunningMarker(runDir)

        val sink = ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = "ranking-overall",
            maxRecords = endpointConfig.maxRecords,
            maxUncompressedBytes = endpointConfig.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = SinkEventPublisher(rankingPublisher),
            volumeMetrics = volumeMetrics,
        )

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(permitsPerSecond)
        val start = Instant.now()
        val dispatcher = workerExecutor.asCoroutineDispatcher()

        log.info("[RankingFetch] starting: runId={}, date={}, maxPages={}, permitsPerSecond={}", runId, date, maxPages, permitsPerSecond)

        return CoroutineScope(dispatcher).future {
            try {
                val (fetched, failed) = processPagesSuspend(sink, rateLimiter, date)
                SchedulerPhaseUtils.logSummary("RankingFetch", fetched, fetched, fetched, failed, start)
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
            val permits = SchedulerPhaseUtils.acquirePermitsSuspend(rateLimiter, 1, 1)
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
                val status = SchedulerPhaseUtils.extractHttpStatus(ex)
                sink.submit(SnapshotChunkRecord.Failure(
                    key = requestKey,
                    endpoint = "ranking-overall",
                    keyType = KeyType.DATE_PAGE.name,
                    httpStatus = status,
                    fetchedAt = Instant.now(),
                    errorMessage = ex.message ?: "unknown",
                ))
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
        val root = objectMapper.readTree(bodyBytes)
        val rankingArray = root.get("ranking")
        if (rankingArray == null || !rankingArray.isArray) {
            log.warn("[RankingFetch] no ranking array in response: page={}", page)
            return 0
        }

        var count = 0
        for (node in rankingArray) {
            val name = node.get("character_name")?.asText() ?: continue
            val entryBytes = objectMapper.writeValueAsBytes(node)
            sink.submit(SnapshotChunkRecord.Success(
                bodyBytes = entryBytes,
                key = name,
                endpoint = "ranking-overall",
                keyType = KeyType.DATE_PAGE.name,
                httpStatus = 200,
                fetchedAt = Instant.now(),
            ))
            count++
        }
        return count
    }
}
