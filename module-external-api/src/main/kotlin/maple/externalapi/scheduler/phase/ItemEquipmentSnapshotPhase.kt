package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.ChunkedSnapshotSink
import maple.externalapi.snapshot.SnapshotChunkRecord
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.slf4j.LoggerFactory
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
class ItemEquipmentSnapshotPhase(
    private val clientPort: ExternalApiClientPort,
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val eventPublisher: SnapshotChunkEventPublisher,
    private val volumeMetrics: SnapshotVolumeMetrics,
    private val metrics: ExternalApiMetrics,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:/data/external-api}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(ItemEquipmentSnapshotPhase::class.java)

    fun execute(executor: ExecutorService, entries: List<Map.Entry<String, String>>) {
        val runId = SchedulerPhaseUtils.newRunId()
        val endpoint = "item-equipment"
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

        log.info("[Scheduler] ========== ITEM_EQUIPMENT lookup start ==========")
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
                    executor.submit(Callable { fetchItemEquipment(ocid, endpoint, sink, successCount, failCount) })
                }

                futures.forEach { it.get() }

                val progress = successCount.get() + failCount.get()
                if (progress - lastProgressLog >= 5000) {
                    lastProgressLog = progress
                    SchedulerPhaseUtils.logProgress("ITEM_EQUIPMENT", progress, entries.size, successCount.get(), failCount.get(), start)
                }
            }
        } finally {
            sink.close()
        }

        metrics.itemEquipmentTimer().record(Duration.between(start, Instant.now()))
        SchedulerPhaseUtils.logSummary("ITEM_EQUIPMENT", entries.size, successCount.get(), successCount.get(), failCount.get(), start)
    }

    private fun fetchItemEquipment(
        ocid: String,
        endpoint: String,
        sink: ChunkedSnapshotSink,
        successCount: java.util.concurrent.atomic.AtomicInteger,
        failCount: java.util.concurrent.atomic.AtomicInteger,
    ) {
        try {
            val bodyBytes = clientPort.fetch(
                ExternalApiProvider.NEXON,
                ExternalApiEndpoint.ITEM_EQUIPMENT,
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
            metrics.recordItemEquipmentFetched()
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
            metrics.recordItemEquipmentFailed()
        }
    }
}
