package maple.calculator.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import maple.calculator.event.SnapshotChunkReadyEvent
import maple.calculator.processor.SnapshotChunkProcessor
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class KafkaSnapshotChunkReadyConsumer(
    private val objectMapper: ObjectMapper,
    private val chunkProcessor: SnapshotChunkProcessor,
) {
    private val log = LoggerFactory.getLogger(KafkaSnapshotChunkReadyConsumer::class.java)

    @KafkaListener(
        topics = ["\${calculator.kafka.snapshot-chunk-ready-topic}"],
        groupId = "\${calculator.kafka.consumer-group-id}",
    )
    fun consume(message: String) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        log.info(
            "[Consumer] received chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            event.runId, event.endpoint, event.chunkId, event.objectKey, event.recordCount,
        )

        if (event.endpoint != "item-equipment") {
            log.info("[Consumer] skipping non-item-equipment endpoint: {}", event.endpoint)
            return
        }

        val result = chunkProcessor.process(event.objectKey)
        log.info(
            "[Consumer] processed chunk: runId={} chunkId={} records={} success={} items={}",
            event.runId, event.chunkId, result.recordCount, result.successCount, result.totalItems,
        )
    }
}
