package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Timer
import maple.expectation.common.event.CalculatorResultChunkReadyEvent
import maple.expectation.common.event.ChunkExecutionIdentity
import maple.expectation.common.event.ChunkExecutionType
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.ManagedLifecycle
import maple.synchronizer.event.KafkaChunkConsumedEventPublisher
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.processor.ChunkProcessInput
import maple.synchronizer.processor.ChunkProcessor
import maple.expectation.common.event.ChunkConsumedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

@Component
class KafkaResultChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val chunkProcessor: ChunkProcessor,
    private val metrics: SynchronizerMetrics,
    private val chunkConsumerTemplate: ChunkConsumerTemplate,
    private val consumedEventPublisher: KafkaChunkConsumedEventPublisher,
) : ManagedLifecycle {
    private val log = LoggerFactory.getLogger(KafkaResultChunkConsumer::class.java)
    private val vtExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val processingPermit = Semaphore(2)

    @KafkaListener(
        topics = ["\${synchronizer.kafka.result-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.consumer-group-id}",
    )
    fun consume(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) messageKey: String?,
    ) {
        val event = objectMapper.readValue(message, CalculatorResultChunkReadyEvent::class.java)
        val runId = event.sourceRunId
        val chunkId = event.sourceChunkId
        val identity = ChunkExecutionIdentity(
            executionType = ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK,
            runId = runId,
            endpoint = event.sourceEndpoint.ifBlank { "result" },
            chunkId = chunkId,
        )

        log.info("[Synchronizer] received: runId={} chunkId={} objectKey={} results={}",
            runId, chunkId, event.objectKey, event.resultCount)

        var chunkSample: Timer.Sample? = null
        chunkConsumerTemplate.submit(
            ChunkConsumerRequest(
                logPrefix = "Synchronizer",
                log = log,
                identity = identity,
                topic = topic ?: event.eventType,
                messageKey = messageKey ?: event.kafkaKey(),
                eventType = event.eventType,
                schemaVersion = event.schemaVersion,
                eventPayloadJson = message,
                objectKey = event.objectKey,
                acknowledgment = acknowledgment,
                processingPermit = processingPermit,
                executor = vtExecutor,
                processContext = TaskContext.of("Synchronizer", "ChunkProcess", chunkId),
                lifecycleContext = TaskContext.of("Synchronizer", "ChunkLifecycle", chunkId),
                mdcValues = mapOf("kafkaTopic" to (topic ?: event.eventType)),
                process = {
                    chunkProcessor.process(ChunkProcessInput(
                        objectKey = event.objectKey,
                        sourceRunId = runId,
                        sourceChunkId = chunkId,
                        resultCount = event.resultCount,
                    ))
                },
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
                    consumedEventPublisher.publish(ChunkConsumedEvent(
                        runId = runId,
                        endpoint = event.sourceEndpoint.ifBlank { "result" },
                        chunkId = chunkId,
                        objectKey = event.objectKey,
                        sourceObjectKey = "runs/${runId}/${event.sourceEndpoint}/chunks/${chunkId}.jsonl.gz",
                    ))
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
