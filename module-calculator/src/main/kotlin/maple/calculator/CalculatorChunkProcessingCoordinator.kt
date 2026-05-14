package maple.calculator

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import maple.calculator.event.CalculatorResultChunkReadyEvent
import maple.calculator.event.KafkaResultEventPublisher
import maple.calculator.event.SnapshotChunkReadyEvent
import maple.calculator.metrics.CalculatorMetrics
import maple.calculator.model.ChunkResult
import maple.calculator.metrics.CalculatorVolumeMetrics
import maple.calculator.processor.SnapshotChunkProcessor
import maple.calculator.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class CalculatorChunkProcessingCoordinator(
    private val chunkProcessor: SnapshotChunkProcessor,
    private val resultEventPublisher: KafkaResultEventPublisher,
    private val objectStorage: ObjectStorage,
    private val metrics: CalculatorMetrics,
    private val volumeMetrics: CalculatorVolumeMetrics,
) {
    private val log = LoggerFactory.getLogger(CalculatorChunkProcessingCoordinator::class.java)
    private val concurrency = Semaphore(2)

    suspend fun handle(event: SnapshotChunkReadyEvent) {
        if (event.endpoint != "item-equipment") {
            log.info("[Coordinator] skipping non-item-equipment endpoint: {}", event.endpoint)
            metrics.recordChunkSkippedEndpoint()
            return
        }

        if (!objectStorage.exists(event.objectKey)) {
            log.error("[Coordinator] source chunk not found: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, event.objectKey)
            metrics.recordChunkSkippedNotFound()
            return
        }

        val resultObjectKey = resultObjectKeyFor(event)
        if (objectStorage.exists(resultObjectKey)) {
            republishExistingResult(event, resultObjectKey)
            return
        }

        withMdc(event) {
            concurrency.withPermit {
                executeChunk(event, resultObjectKey)
            }
        }
    }

    fun resultObjectKeyFor(event: SnapshotChunkReadyEvent): String =
        "data/calculator/runs/${event.runId}/${event.endpoint}/chunks/result-${event.chunkId}.jsonl.gz"

    private suspend fun republishExistingResult(event: SnapshotChunkReadyEvent, resultObjectKey: String) {
        log.info("[Coordinator] result already exists, republishing: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, resultObjectKey)
        metrics.recordChunkSkippedIdempotent()
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
            metrics.timer().record(Duration.ofNanos(System.nanoTime() - start))
            onChunkProcessed(event, result, start)
        }.onFailure { ex ->
            log.error("[Coordinator] chunk processing failed: runId={} chunkId={}: {}", event.runId, event.chunkId, ex.message, ex)
            metrics.recordChunkFailed()
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
            event.runId, event.chunkId,
            result.recordCount, result.successCount, result.totalItems, result.resultCount, result.errorCount,
        )
        volumeMetrics.recordInput(event.compressedBytes, event.uncompressedBytes)
        volumeMetrics.recordResult(result.resultCompressedBytes, result.resultUncompressedBytes, result.resultCount.toLong())
        val ratio = if (result.resultCompressedBytes > 0) "%.2f".format(result.resultUncompressedBytes.toDouble() / result.resultCompressedBytes.toDouble()) else "N/A"
        log.info(
            "[calculatorArtifactVolume] runId={} chunkId={} inputCompressedBytes={} inputUncompressedBytes={} resultCompressedBytes={} resultUncompressedBytes={} resultJsonRows={} resultCompressionRatio={}",
            event.runId, event.chunkId, event.compressedBytes, event.uncompressedBytes,
            result.resultCompressedBytes, result.resultUncompressedBytes, result.resultCount, ratio,
        )
        metrics.recordChunkProcessed()
        metrics.recordUsers(result.recordCount)
        metrics.recordItems(result.totalItems)
        metrics.recordCalculated(result.resultCount)
        metrics.recordErrors(result.errorCount)
        val durationSec = (System.nanoTime() - startNanos) / 1_000_000_000.0
        metrics.recordChunkRates(result.recordCount, result.totalItems, durationSec)
    }

    private suspend fun <T> withMdc(event: SnapshotChunkReadyEvent, block: suspend () -> T): T {
        MDC.put("runId", event.runId)
        MDC.put("chunkId", event.chunkId)
        MDC.put("kafkaTopic", "external-api.snapshot.chunk-ready")
        return try {
            withContext(MDCContext()) { block() }
        } finally {
            MDC.clear()
        }
    }
}
