package maple.calculator

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import maple.calculator.event.ChunkProcessingEvent
import maple.calculator.event.KafkaResultEventPublisher
import maple.calculator.metrics.CalculatorMetricsListener
import maple.calculator.model.ChunkResult
import maple.calculator.processor.SnapshotChunkProcessor
import maple.calculator.storage.ObjectStorage
import maple.expectation.common.event.CalculatorResultChunkReadyEvent
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.util.CompressionUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Coordinates chunk processing for calculator.
 *
 * Caps in-flight chunk processing at [CHUNK_PROCESS_PERMITS] (= 4) to bound
 * heap pressure. The Kafka listener container pulls up to 50 records per poll
 * across 4 listener threads (200 chunks in flight at the transport level), but
 * each chunk holds ~12 MB of pipeline state (Flow buffers + parsed objects +
 * Caffeine entries + intermediate calc maps). Without this cap, 200 × 12 MB
 * overflows the 2 GB JVM heap within minutes of restart.
 *
 * Disk-stat calls ([ObjectStorage.exists]) are wrapped in [withContext] on the
 * virtual-thread dispatcher so they do not pin a Kafka listener platform
 * thread. The semaphore is acquired only for the actual chunk processing, not
 * for the cheap disk stats, so the stat check does not contend with the pipeline.
 */
@Component
class CalculatorChunkProcessingCoordinator(
    private val chunkProcessor: SnapshotChunkProcessor,
    private val resultEventPublisher: KafkaResultEventPublisher,
    private val objectStorage: ObjectStorage,
    private val metricsListener: CalculatorMetricsListener,
    @Qualifier("vtDispatcher") private val vtDispatcher: CoroutineDispatcher,
) {
    private val log = LoggerFactory.getLogger(CalculatorChunkProcessingCoordinator::class.java)
    private val pipelinePermits = Semaphore(CHUNK_PROCESS_PERMITS)

    suspend fun handle(event: SnapshotChunkReadyEvent) {
        if (event.endpoint != "item-equipment") {
            log.info("[Coordinator] skipping non-item-equipment endpoint: {}", event.endpoint)
            metricsListener.onEvent(ChunkProcessingEvent.Skipped(event.runId, event.chunkId, "endpoint_mismatch"))
            return
        }

        withMdc(event) {
            if (!withContext(vtDispatcher) { objectStorage.exists(event.objectKey) }) {
                log.error("[Coordinator] source chunk not found: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, event.objectKey)
                metricsListener.onEvent(ChunkProcessingEvent.Skipped(event.runId, event.chunkId, "source_not_found"))
                return@withMdc
            }

            val resultObjectKey = resultObjectKeyFor(event)
            if (withContext(vtDispatcher) { objectStorage.exists(resultObjectKey) }) {
                republishExistingResult(event, resultObjectKey)
                return@withMdc
            }

            pipelinePermits.withPermit {
                executeChunk(event, resultObjectKey)
            }
        }
    }

    fun resultObjectKeyFor(event: SnapshotChunkReadyEvent): String = "calculator/runs/${event.runId}/${event.endpoint}/chunks/result-${event.chunkId}.jsonl.gz"

    private suspend fun republishExistingResult(event: SnapshotChunkReadyEvent, resultObjectKey: String) {
        log.info("[Coordinator] result already exists, republishing: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, resultObjectKey)
        metricsListener.onEvent(ChunkProcessingEvent.Skipped(event.runId, event.chunkId, "result_exists"))
        resultEventPublisher.publishChunkReady(
            CalculatorResultChunkReadyEvent(
                sourceRunId = event.runId,
                sourceEndpoint = event.endpoint,
                sourceChunkId = event.chunkId,
                objectKey = resultObjectKey,
                sourceRecordCount = event.recordCount,
                resultCount = 0,
                errorCount = 0,
                uncompressedBytes = 0,
                compressedBytes = 0,
            ),
        )
    }

    private suspend fun executeChunk(event: SnapshotChunkReadyEvent, resultObjectKey: String) {
        val start = System.nanoTime()
        runCatching {
            val result = chunkProcessor.process(event, resultObjectKey)
            onChunkProcessed(event, result, start)
        }.onFailure { ex ->
            log.error("[Coordinator] chunk processing failed: runId={} chunkId={}: {}", event.runId, event.chunkId, ex.message, ex)
            metricsListener.onEvent(ChunkProcessingEvent.Failed(event.runId, event.chunkId))
            throw ex
        }
    }

    private suspend fun onChunkProcessed(
        event: SnapshotChunkReadyEvent,
        result: ChunkResult,
        startNanos: Long,
    ) {
        resultEventPublisher.publishChunkReady(
            CalculatorResultChunkReadyEvent(
                sourceRunId = event.runId,
                sourceEndpoint = event.endpoint,
                sourceChunkId = event.chunkId,
                objectKey = result.resultObjectKey,
                sourceRecordCount = event.recordCount,
                resultCount = result.resultCount,
                errorCount = result.errorCount,
                uncompressedBytes = result.resultUncompressedBytes,
                compressedBytes = result.resultCompressedBytes,
            ),
        )
        log.info(
            "[Coordinator] processed chunk: runId={} chunkId={} records={} success={} items={} results={} errors={}",
            event.runId,
            event.chunkId,
            result.recordCount,
            result.successCount,
            result.totalItems,
            result.resultCount,
            result.errorCount,
        )
        val ratio = CompressionUtils.ratioString(result.resultUncompressedBytes, result.resultCompressedBytes)
        log.info(
            "[calculatorArtifactVolume] runId={} chunkId={} inputCompressedBytes={} inputUncompressedBytes={} resultCompressedBytes={} resultUncompressedBytes={} resultJsonRows={} resultCompressionRatio={}",
            event.runId, event.chunkId, event.compressedBytes, event.uncompressedBytes,
            result.resultCompressedBytes, result.resultUncompressedBytes, result.resultCount, ratio,
        )
        metricsListener.onEvent(
            ChunkProcessingEvent.Completed(
                runId = event.runId,
                chunkId = event.chunkId,
                recordCount = result.recordCount,
                totalItems = result.totalItems,
                resultCount = result.resultCount,
                errorCount = result.errorCount,
                inputCompressedBytes = event.compressedBytes,
                inputUncompressedBytes = event.uncompressedBytes,
                resultCompressedBytes = result.resultCompressedBytes,
                resultUncompressedBytes = result.resultUncompressedBytes,
                durationNanos = System.nanoTime() - startNanos,
            ),
        )
    }

    private suspend fun <T> withMdc(event: SnapshotChunkReadyEvent, block: suspend () -> T): T = withContext(
        MDCContext(
            mapOf(
                "runId" to event.runId,
                "chunkId" to event.chunkId,
                "kafkaTopic" to "external-api.snapshot.chunk-ready",
            ),
        ),
    ) { block() }
}

private const val CHUNK_PROCESS_PERMITS: Int = 4
