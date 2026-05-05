package maple.calculator.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import maple.calculator.event.CalculatorResultChunkReadyEvent
import maple.calculator.event.KafkaResultEventPublisher
import maple.calculator.event.SnapshotChunkReadyEvent
import maple.calculator.processor.SnapshotChunkProcessor
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class KafkaSnapshotChunkReadyConsumer(
    private val objectMapper: ObjectMapper,
    private val chunkProcessor: SnapshotChunkProcessor,
    private val resultEventPublisher: KafkaResultEventPublisher,
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
            acknowledgment.acknowledge()
            return
        }

        runBlocking {
            concurrency.withPermit {
                runCatching {
                    val result = chunkProcessor.process(event)
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
                    acknowledgment.acknowledge()
                }.onFailure { ex ->
                    log.error("[Consumer] chunk processing failed: runId={} chunkId={}: {}", event.runId, event.chunkId, ex.message)
                }
            }
        }
    }
}
