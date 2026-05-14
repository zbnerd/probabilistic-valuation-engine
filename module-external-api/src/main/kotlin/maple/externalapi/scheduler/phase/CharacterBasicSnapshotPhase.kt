package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.port.out.ExternalApiArtifactStorePort
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
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class CharacterBasicSnapshotPhase(
    private val clientPort: ExternalApiClientPort,
    private val artifactStore: ExternalApiArtifactStorePort,
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val volumeMetrics: SnapshotVolumeMetrics,
    private val metrics: ExternalApiMetrics,
    @Qualifier("characterBasicSnapshotPublisher")
    private val eventPublisher: SnapshotChunkEventPublisher,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:/data/external-api}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(CharacterBasicSnapshotPhase::class.java)

    fun execute(executor: ExecutorService, ocidCache: Map<String, String>) {
        val existingBasic = artifactStore.listStoredKeys(ExternalApiEndpoint.CHARACTER_BASIC)
        if (existingBasic.isNotEmpty()) {
            log.info("[Scheduler] CHARACTER_BASIC already done ({} files), skipping", existingBasic.size)
            return
        }

        val entries = ocidCache.entries.toList()
        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping CHARACTER_BASIC")
            return
        }

        val runId = SchedulerPhaseUtils.newRunId()
        val endpoint = "character-basic"
        val config = chunkingProperties.configFor(endpoint)
        val runDir = Paths.get(storeBasePath, "runs", runId)
        SchedulerPhaseUtils.writeRunningMarker(runDir)
        val sink = ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = endpoint,
            maxRecords = config.maxRecords,
            maxUncompressedBytes = config.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = eventPublisher,
            volumeMetrics = volumeMetrics,
        )

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(permitsPerSecond)

        log.info("[Scheduler] ========== CHARACTER_BASIC lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, chunk={}records/{}bytes, runId={}",
            entries.size, permitsPerSecond, batchSize, config.maxRecords, config.maxUncompressedBytes, runId,
        )

        val start = Instant.now()
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failCount = java.util.concurrent.atomic.AtomicInteger(0)
        var processed = 0
        var lastProgressLog = 0

        try {
            while (processed < entries.size) {
                val permits = SchedulerPhaseUtils.acquirePermits(rateLimiter, batchSize, entries.size - processed)
                if (permits == 0) continue

                val chunk = entries.subList(processed, processed + permits)
                processed += permits

                val futures = chunk.map { (ign, ocid) ->
                    executor.submit(
                        Callable {
                            try {
                                val bodyBytes = clientPort.fetch(
                                    ExternalApiProvider.NEXON,
                                    ExternalApiEndpoint.CHARACTER_BASIC,
                                    ocid,
                                ).join()
                                sink.submit(
                                    SnapshotChunkRecord.Success(
                                        key = ocid,
                                        endpoint = endpoint,
                                        keyType = "OCID",
                                        httpStatus = 200,
                                        fetchedAt = Instant.now(),
                                        bodyBytes = bodyBytes,
                                    ),
                                )
                                successCount.incrementAndGet()
                                metrics.recordCharacterBasicFetched()
                            } catch (ex: Exception) {
                                val httpStatus = SchedulerPhaseUtils.extractHttpStatus(ex)
                                sink.submit(
                                    SnapshotChunkRecord.Failure(
                                        key = ocid,
                                        endpoint = endpoint,
                                        keyType = "OCID",
                                        httpStatus = httpStatus,
                                        fetchedAt = Instant.now(),
                                        errorMessage = ex.message ?: "unknown",
                                    ),
                                )
                                failCount.incrementAndGet()
                                metrics.recordCharacterBasicFailed()
                            }
                        },
                    )
                }

                futures.forEach { it.get() }

                val progress = successCount.get() + failCount.get()
                if (progress - lastProgressLog >= 5000) {
                    lastProgressLog = progress
                    SchedulerPhaseUtils.logProgress("CHARACTER_BASIC", progress, entries.size, successCount.get(), failCount.get(), start)
                }
            }
        } finally {
            sink.close()
        }

        metrics.characterBasicTimer().record(Duration.between(start, Instant.now()))
        SchedulerPhaseUtils.logSummary("CHARACTER_BASIC", entries.size, successCount.get(), successCount.get(), failCount.get(), start)
    }
}
