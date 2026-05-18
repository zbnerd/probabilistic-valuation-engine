package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Timer
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.common.event.CalculatorResultChunkReadyEvent
import maple.expectation.infrastructure.lifecycle.ManagedLifecycle
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.processor.ChunkProcessInput
import maple.synchronizer.processor.ChunkProcessor
import maple.synchronizer.repository.SynchronizerChunkStatusRepository
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

@Component
class KafkaResultChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val chunkProcessor: ChunkProcessor,
    private val chunkStatusRepository: SynchronizerChunkStatusRepository,
    private val metrics: SynchronizerMetrics,
    private val logicExecutor: LogicExecutor,
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

        if (chunkStatusRepository.isAlreadySuccess(runId, chunkId)) {
            log.info("[Synchronizer] skip already-successful chunk: runId={} chunkId={}", runId, chunkId)
            acknowledgment.acknowledge()
            return
        }

        if (!chunkStatusRepository.claimChunk(runId, chunkId, event.objectKey)) {
            log.info("[Synchronizer] skip - chunk already claimed by another worker: runId={} chunkId={}", runId, chunkId)
            acknowledgment.acknowledge()
            return
        }

        if (!processingPermit.tryAcquire()) {
            log.info("[Synchronizer] processing permit busy, will retry: runId={} chunkId={}", runId, chunkId)
            return
        }

        metrics.incrementReceived()
        metrics.incrementProcessing()
        MDC.put("runId", runId)
        MDC.put("chunkId", chunkId)
        MDC.put("kafkaTopic", "calculator.result.chunk-ready")

        CompletableFuture.runAsync({
            val chunkSample = Timer.start()
            logicExecutor.executeWithFinally(
                task = {
                    logicExecutor.executeOrCatch(
                        task = {
                            chunkProcessor.process(ChunkProcessInput(
                                objectKey = event.objectKey,
                                sourceRunId = runId,
                                sourceChunkId = chunkId,
                                resultCount = event.resultCount,
                            ))
                            chunkStatusRepository.markSuccess(runId, chunkId)
                            metrics.incrementProcessed()
                            metrics.recordStatusTransition("SUCCESS")
                            chunkSample.stop(metrics.chunkTimer())

                            metrics.recordChunkBytes(event.compressedBytes)
                            recordPreUpsertVolume(event)
                            acknowledgment.acknowledge()
                        },
                        recovery = { ex ->
                            chunkStatusRepository.markFailed(runId, chunkId, ex.message ?: "unknown")
                            metrics.incrementFailed()
                            metrics.recordStatusTransition("FAILED")
                            log.error("[Synchronizer] chunk processing failed: runId={} chunkId={}", runId, chunkId, ex)
                            null
                        },
                        context = TaskContext.of("Synchronizer", "ChunkProcess", chunkId),
                    )
                },
                finallyBlock = {
                    metrics.decrementProcessing()
                    processingPermit.release()
                    MDC.clear()
                },
                context = TaskContext.of("Synchronizer", "ChunkLifecycle", chunkId),
            )
        }, vtExecutor)
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
