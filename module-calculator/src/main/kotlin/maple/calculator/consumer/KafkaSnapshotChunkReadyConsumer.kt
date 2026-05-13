package maple.calculator.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import maple.calculator.event.CalculatorResultChunkReadyEvent
import maple.calculator.event.KafkaResultEventPublisher
import maple.calculator.event.SnapshotChunkReadyEvent
import maple.calculator.metrics.CalculatorMetrics
import maple.calculator.metrics.CalculatorVolumeMetrics
import maple.calculator.processor.SnapshotChunkProcessor
import maple.calculator.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Duration
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class KafkaSnapshotChunkReadyConsumer(
    private val objectMapper: ObjectMapper,
    private val chunkProcessor: SnapshotChunkProcessor,
    private val resultEventPublisher: KafkaResultEventPublisher,
    private val objectStorage: ObjectStorage,
    private val metrics: CalculatorMetrics,
    private val volumeMetrics: CalculatorVolumeMetrics,
) {
    private val log = LoggerFactory.getLogger(KafkaSnapshotChunkReadyConsumer::class.java)
    private val concurrency = Semaphore(2)

    @KafkaListener(
        topics = ["\${calculator.kafka.snapshot-chunk-ready-topic}"],
        groupId = "\${calculator.kafka.consumer-group-id}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        log.info(
            "[Consumer] received chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            event.runId,
            event.endpoint,
            event.chunkId,
            event.objectKey,
            event.recordCount,
        )

        if (event.endpoint != "item-equipment") {
            log.info("[Consumer] skipping non-item-equipment endpoint: {}", event.endpoint)
            metrics.recordChunkSkippedEndpoint()
            acknowledgment.acknowledge()
            return
        }

        if (!objectStorage.exists(event.objectKey)) {
            log.error("[Consumer] source chunk not found, skipping: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, event.objectKey)
            metrics.recordChunkSkippedNotFound()
            acknowledgment.acknowledge()
            return
        }

        val resultObjectKey = "data/calculator/runs/${event.runId}/${event.endpoint}/chunks/result-${event.chunkId}.jsonl.gz"
        if (objectStorage.exists(resultObjectKey)) {
            log.info("[Consumer] result already exists, republishing event: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, resultObjectKey)
            metrics.recordChunkSkippedIdempotent()
            runBlocking {
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
            acknowledgment.acknowledge()
            return
        }

        MDC.put("runId", event.runId)
        MDC.put("chunkId", event.chunkId)
        MDC.put("kafkaTopic", "external-api.snapshot.chunk-ready")
        try {
            runBlocking {
                concurrency.withPermit {
                    val start = System.nanoTime()
                    runCatching {
                        val result = chunkProcessor.process(event)
                        metrics.timer().record(Duration.ofNanos(System.nanoTime() - start))
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
                        result
                    }.onSuccess { result ->
                        log.info(
                            "[Consumer] processed chunk: runId={} chunkId={} records={} success={} items={} results={} errors={}",
                            event.runId,
                            event.chunkId,
                            result.recordCount,
                            result.successCount,
                            result.totalItems,
                            result.resultCount,
                            result.errorCount,
                        )
                        volumeMetrics.recordInput(event.compressedBytes, event.uncompressedBytes)
                        volumeMetrics.recordResult(result.resultCompressedBytes, result.resultUncompressedBytes, result.resultCount.toLong())
                        val resultRatio = if (result.resultCompressedBytes > 0) "%.2f".format(result.resultUncompressedBytes.toDouble() / result.resultCompressedBytes.toDouble()) else "N/A"
                        log.info(
                            "[calculatorArtifactVolume] runId={} chunkId={} inputCompressedBytes={} inputUncompressedBytes={} resultCompressedBytes={} resultUncompressedBytes={} resultJsonRows={} resultCompressionRatio={}",
                            event.runId, event.chunkId, event.compressedBytes, event.uncompressedBytes,
                            result.resultCompressedBytes, result.resultUncompressedBytes, result.resultCount, resultRatio,
                        )
                        metrics.recordChunkProcessed()
                        metrics.recordUsers(result.recordCount)
                        metrics.recordItems(result.totalItems)
                        metrics.recordCalculated(result.resultCount)
                        metrics.recordErrors(result.errorCount)
                        val durationSec = (System.nanoTime() - start) / 1_000_000_000.0
                        metrics.recordChunkRates(result.recordCount, result.totalItems, durationSec)
                        acknowledgment.acknowledge()
                    }.onFailure { ex ->
                        log.error("[Consumer] chunk processing failed, skipping: runId={} chunkId={}: {}", event.runId, event.chunkId, ex.message, ex)
                        metrics.recordChunkFailed()
                        acknowledgment.acknowledge()
                    }
                }
            }
        } finally {
            MDC.clear()
        }
    }
}
