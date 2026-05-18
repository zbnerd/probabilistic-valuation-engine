package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Timer
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.common.event.CalculatorResultChunkReadyEvent
import maple.expectation.infrastructure.lifecycle.ManagedLifecycle
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.processor.ChunkProcessInput
import maple.synchronizer.processor.ChunkProcessor
import maple.synchronizer.repository.SynchronizerChunkStatusRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

@Component
class KafkaResultChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val chunkProcessor: ChunkProcessor,
    private val chunkStatusRepository: SynchronizerChunkStatusRepository,
    private val metrics: SynchronizerMetrics,
    private val chunkConsumerTemplate: ChunkConsumerTemplate,
		) : ManagedLifecycle {
    private val log = LoggerFactory.getLogger(KafkaResultChunkConsumer::class.java)
    private val vtExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val processingPermit = Semaphore(2)

    @KafkaListener(
        topics = ["\${synchronizer.kafka.result-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.consumer-group-id}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, CalculatorResultChunkReadyEvent::class.java)
        val runId = event.sourceRunId
        val chunkId = event.sourceChunkId

        log.info("[Synchronizer] received: runId={} chunkId={} objectKey={} results={}",
            runId, chunkId, event.objectKey, event.resultCount)

        var chunkSample: Timer.Sample? = null
        chunkConsumerTemplate.submit(
            ChunkConsumerRequest(
                logPrefix = "Synchronizer",
                log = log,
                runId = runId,
                chunkId = chunkId,
                objectKey = event.objectKey,
                acknowledgment = acknowledgment,
                processingPermit = processingPermit,
                executor = vtExecutor,
                processContext = TaskContext.of("Synchronizer", "ChunkProcess", chunkId),
                lifecycleContext = TaskContext.of("Synchronizer", "ChunkLifecycle", chunkId),
                mdcValues = mapOf("kafkaTopic" to "calculator.result.chunk-ready"),
                isAlreadySuccess = { chunkStatusRepository.isAlreadySuccess(runId, chunkId) },
                claimChunk = { chunkStatusRepository.claimChunk(runId, chunkId, event.objectKey) },
                process = {
                    chunkProcessor.process(ChunkProcessInput(
                        objectKey = event.objectKey,
                        sourceRunId = runId,
                        sourceChunkId = chunkId,
                        resultCount = event.resultCount,
                    ))
                },
                markSuccess = { chunkStatusRepository.markSuccess(runId, chunkId) },
                markFailed = { reason -> chunkStatusRepository.markFailed(runId, chunkId, reason) },
                onAccepted = {
                    chunkSample = Timer.start()
                    metrics.incrementReceived()
                    metrics.incrementProcessing()
                },
                onSuccess = {
                    metrics.incrementProcessed()
                    metrics.recordStatusTransition("SUCCESS")
                    chunkSample?.stop(metrics.chunkTimer())
                    metrics.recordChunkBytes(event.compressedBytes)
                    recordPreUpsertVolume(event)
                },
                onFailure = { ex ->
                    metrics.incrementFailed()
                    metrics.recordStatusTransition("FAILED")
                    log.error("[Synchronizer] chunk processing failed: runId={} chunkId={}", runId, chunkId, ex)
                },
                onFinally = { metrics.decrementProcessing() },
            ),
        )
    }

    private fun recordPreUpsertVolume(event: CalculatorResultChunkReadyEvent) {
        metrics.recordPreUpsertVolume(event.compressedBytes, event.uncompressedBytes, event.resultCount.toLong())
        val ratio = if (event.compressedBytes > 0)
            "%.2f".format(event.uncompressedBytes.toDouble() / event.compressedBytes.toDouble())
        else "N/A"
        log.info(
            "[preUpsertVolume] runId={} chunkId={} compressedBytes={} uncompressedBytes={} jsonRows={} compressionRatio={}",
            event.sourceRunId, event.sourceChunkId, event.compressedBytes, event.uncompressedBytes,
            event.resultCount, ratio,
        )
    }

    override val lifecyclePhase: Int = 100

    override fun stopLifecycle() {
        vtExecutor.close()
    }
}
