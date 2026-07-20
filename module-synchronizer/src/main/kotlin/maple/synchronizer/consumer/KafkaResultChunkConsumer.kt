package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import maple.core.domain.chunk.ChunkProcessInput
import maple.expectation.common.event.CalculatorResultChunkReadyEvent
import maple.expectation.common.event.ChunkConsumedEvent
import maple.expectation.common.event.ChunkExecutionIdentity
import maple.expectation.common.event.ChunkExecutionType
import maple.expectation.util.CompressionUtils
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.synchronizer.event.KafkaChunkConsumedEventPublisher
import maple.synchronizer.event.ResultChunkEventPathBuilder
import maple.synchronizer.metrics.SynchronizerChunkMetricsListener
import maple.synchronizer.processor.ChunkProcessor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class KafkaResultChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val chunkProcessor: ChunkProcessor,
    private val chunkMetricsListener: SynchronizerChunkMetricsListener,
    private val chunkConsumerTemplate: ChunkConsumerTemplate,
    private val consumedEventPublisher: KafkaChunkConsumedEventPublisher,
    private val eventPathBuilder: ResultChunkEventPathBuilder,
    @Qualifier("kafkaResultChunkExecutor") private val executor: ExecutorService,
) {
    private val log = LoggerFactory.getLogger(KafkaResultChunkConsumer::class.java)
    private val processingPermit = Semaphore(2)

    fun consume(
        message: String,
        context: DeliveryContext,
    ): CompletionStage<DeliveryOutcome> {
        val event = runCatching {
            objectMapper.readValue(message, CalculatorResultChunkReadyEvent::class.java)
        }.getOrElse {
            return CompletableFuture.completedFuture(DeliveryOutcome.InvalidMessage(INVALID_MESSAGE))
        }
        val runId = event.sourceRunId
        val chunkId = event.sourceChunkId
        val identity = ChunkExecutionIdentity(
            executionType = ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK,
            runId = runId,
            endpoint = event.sourceEndpoint.ifBlank { "result" },
            chunkId = chunkId,
        )

        log.info(
            "[Synchronizer] received: runId={} chunkId={} objectKey={} results={}",
            runId,
            chunkId,
            event.objectKey,
            event.resultCount,
        )

        val startNanos = System.nanoTime()
        chunkMetricsListener.onEvent(ChunkLifecycleEvent.Accepted(runId, chunkId))
        return chunkConsumerTemplate.submit(
            ChunkConsumerRequest(
                identity = identity,
                topic = context.topic,
                messageKey = context.key ?: event.kafkaKey(),
                eventType = event.eventType,
                schemaVersion = event.schemaVersion,
                eventPayloadJson = message,
                processingPermit = processingPermit,
                executor = executor,
                process = {
                    chunkProcessor.process(
                        ChunkProcessInput(
                            objectKey = event.objectKey,
                            sourceRunId = runId,
                            sourceChunkId = chunkId,
                            resultCount = event.resultCount,
                        ),
                    )
                },
                publishRequired = {
                    consumedEventPublisher.publish(
                        ChunkConsumedEvent(
                            runId = runId,
                            endpoint = event.sourceEndpoint.ifBlank { "result" },
                            chunkId = chunkId,
                            objectKey = event.objectKey,
                            sourceObjectKey = eventPathBuilder.sourceObjectKey(
                                runId = runId,
                                sourceEndpoint = event.sourceEndpoint.ifBlank { "result" },
                                chunkId = chunkId,
                            ),
                        ),
                    )
                },
                onObservedSuccess = {
                    chunkMetricsListener.onEvent(
                        ChunkLifecycleEvent.Succeeded(
                            runId = runId,
                            chunkId = chunkId,
                            compressedBytes = event.compressedBytes,
                            uncompressedBytes = event.uncompressedBytes,
                            resultCount = event.resultCount.toLong(),
                            durationNanos = System.nanoTime() - startNanos,
                        ),
                    )
                    logPreUpsertVolume(event)
                },
                onObservedFailure = { ex ->
                    chunkMetricsListener.onEvent(ChunkLifecycleEvent.Failed(runId, chunkId))
                    log.error("[Synchronizer] chunk processing failed: runId={} chunkId={}", runId, chunkId, ex)
                },
            ),
        ).whenComplete { _, _ ->
            chunkMetricsListener.onEvent(ChunkLifecycleEvent.Finally(runId, chunkId))
        }
    }

    private fun logPreUpsertVolume(event: CalculatorResultChunkReadyEvent) {
        val ratio = CompressionUtils.ratioString(event.uncompressedBytes, event.compressedBytes)
        log.info(
            "[preUpsertVolume] runId={} chunkId={} compressedBytes={} uncompressedBytes={} jsonRows={} compressionRatio={}",
            event.sourceRunId,
            event.sourceChunkId,
            event.compressedBytes,
            event.uncompressedBytes,
            event.resultCount,
            ratio,
        )
    }

    private companion object {
        private const val INVALID_MESSAGE = "INVALID_MESSAGE"
    }
}
