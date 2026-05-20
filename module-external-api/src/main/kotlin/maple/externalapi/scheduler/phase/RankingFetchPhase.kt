package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.domain.KeyType
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.ChunkedSnapshotSink
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
import java.util.concurrent.atomic.AtomicInteger

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
    @Value("\${external-api.store.base-path:/data/external-api}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(RankingFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService): CompletableFuture<Path> {
        val runId = SchedulerPhaseUtils.newRunId()
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
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
            eventPublisher = rankingPublisher,
            volumeMetrics = volumeMetrics,
        )

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(permitsPerSecond)
        val fetched = AtomicInteger(0)
        val failed = AtomicInteger(0)

        log.info("[RankingFetch] starting: runId={}, date={}, maxPages={}, permitsPerSecond={}", runId, date, maxPages, permitsPerSecond)
        val start = Instant.now()

        return processPages(workerExecutor, sink, rateLimiter, date, 1, fetched, failed)
            .whenComplete { _, ex ->
                sink.close()
                if (ex != null) {
                    log.error("[RankingFetch] failed: runId={}, fetched={}, failed={}", runId, fetched.get(), failed.get(), ex)
                } else {
                    SchedulerPhaseUtils.logSummary("RankingFetch", fetched.get(), fetched.get(), fetched.get(), failed.get(), start)
                }
            }
            .thenApply { runDir }
    }

    private fun processPages(
        workerExecutor: ExecutorService,
        sink: ChunkedSnapshotSink,
        rateLimiter: io.github.bucket4j.Bucket,
        date: String,
        currentPage: Int,
        fetched: AtomicInteger,
        failed: AtomicInteger,
    ): CompletableFuture<Void> {
        if (currentPage > maxPages) {
            return CompletableFuture.completedFuture(null)
        }

        SchedulerPhaseUtils.acquirePermits(rateLimiter, 1, 1)

        val requestKey = "$date:$currentPage"
        return clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, requestKey)
            .thenAcceptAsync({ bodyBytes ->
                val count = submitRankingEntries(sink, bodyBytes, currentPage)
                fetched.addAndGet(count)
                metrics.recordRankingFetched(count)
                if (fetched.get() % 10000 == 0) {
                    log.info("[RankingFetch] progress: fetched={}, failed={}, page={}/{}", fetched.get(), failed.get(), currentPage, maxPages)
                }
            }, workerExecutor)
            .handle { _, ex ->
                if (ex != null) {
                    failed.incrementAndGet()
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
                null
            }
            .thenCompose { processPages(workerExecutor, sink, rateLimiter, date, currentPage + 1, fetched, failed) }
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
