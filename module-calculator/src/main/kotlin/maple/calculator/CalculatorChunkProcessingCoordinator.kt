package maple.calculator

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import maple.calculator.config.PipelineProperties
import maple.calculator.event.ChunkProcessingEvent
import maple.calculator.event.KafkaResultEventPublisher
import maple.calculator.metrics.CalculatorMetricsListener
import maple.calculator.model.ChunkResult
import maple.calculator.processor.SnapshotChunkProcessor
import maple.calculator.runstate.CalculatorCurrentRunIdHolder
import maple.expectation.common.event.CalculatorResultChunkReadyEvent
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.util.CompressionUtils
import maple.pipeline.artifact.identity.CalculatorArtifactLayout
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
 *
 * Source-chunk existence is verified with retry/backoff per
 * [PipelineProperties.sourceChunkRetryDelaysMs]. The previous single-shot
 * check observed ~10% spurious NoSuchKeyException under load (MinIO headObject
 * race against ext-api's PUT), causing most chunks to be dropped as
 * `source_not_found` even though the chunk was on disk. The retry schedule
 * (default `[0, 100, 300, 1000, 3000]` ms — 5 attempts over ~4.4s) recovers
 * most race victims without changing ext-api's write-then-publish order.
 */
@Component
class CalculatorChunkProcessingCoordinator(
    private val chunkProcessor: SnapshotChunkProcessor,
    private val resultEventPublisher: KafkaResultEventPublisher,
    private val objectStorage: ObjectStorage,
    private val metricsListener: CalculatorMetricsListener,
    private val currentRunIdHolder: CalculatorCurrentRunIdHolder,
    private val pipelineProperties: PipelineProperties,
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

        // runId tracking is intentionally not enforced here. Two valid runId
        // sources exist:
        //  - daily runId (polled from ext-api /api/internal/run-status)
        //  - per-cycle runIds emitted by ext-api's `ExternalApiScheduler.runItemEquipmentPhase`
        //    (a different ID per cycle, designed as a log-correlation handle)
        // The current policy is: trust the source chunk's existence as the
        // sole ground truth. A missing chunk → source_not_found. A present
        // chunk → process (the result_exists check below makes this safe
        // for events that arrive twice or after a replay).
        //
        // Urgent-path chunks (runId prefix `urgent-`) follow the same path;
        // they always have a corresponding chunk since the urgent producer
        // writes synchronously before publishing the event.
        withMdc(event) {
            if (!waitForSourceChunk(event.objectKey)) {
                log.error("[Coordinator] source chunk not found after retries: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, event.objectKey)
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

    fun resultObjectKeyFor(event: SnapshotChunkReadyEvent): String =
        CalculatorArtifactLayout.resultChunk(event.runId, event.endpoint, event.chunkId).value

    /**
     * Probe the source chunk with retry/backoff. Returns true as soon as a
     * probe succeeds. The schedule is configurable via
     * [PipelineProperties.sourceChunkRetryDelaysMs] — each entry is a delay
     * in ms applied before that attempt (the first entry should be 0 for
     * "try immediately"). The total worst-case wait is the sum of all delays.
     *
     * Rationale: MinIO headObject can return NoSuchKeyException transiently
     * for an object that does exist on disk, when the producer's PUT is in
     * flight. Retrying resolves the race in well under a second for the
     * vast majority of cases; only true missing chunks exhaust the schedule.
     */
    private suspend fun waitForSourceChunk(objectKey: String): Boolean {
        val delays = pipelineProperties.sourceChunkRetryDelaysMs
        for ((attempt, delayMs) in delays.withIndex()) {
            if (delayMs > 0) delay(delayMs)
            if (withContext(vtDispatcher) { objectStorage.exists(objectKey) }) {
                if (attempt > 0) {
                    log.info(
                        "[Coordinator] source chunk found after {} retries: key={} (delaysMs={})",
                        attempt,
                        objectKey,
                        delays,
                    )
                }
                return true
            }
        }
        return false
    }

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
