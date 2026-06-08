package maple.calculator.consumer

import maple.calculator.parser.SnapshotEventParser
import maple.expectation.common.event.SnapshotChunkReadyEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class KafkaSnapshotChunkReadyConsumer(
    private val eventParser: SnapshotEventParser,
    private val dispatchService: SnapshotDispatchService,
) {
    private val log = LoggerFactory.getLogger(KafkaSnapshotChunkReadyConsumer::class.java)

    @KafkaListener(
        topics = ["\${calculator.kafka.snapshot-chunk-ready-topic}"],
        groupId = "\${calculator.kafka.consumer-group-id}",
    )
    suspend fun consume(message: String, acknowledgment: Acknowledgment) {
        val event = eventParser.parse(message)
        log.info(
            "[Consumer] received chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            event.runId, event.endpoint, event.chunkId, event.objectKey, event.recordCount,
        )
        dispatchService.dispatch(event, acknowledgment, label = "Consumer")
    }

    @KafkaListener(
        topics = ["\${calculator.kafka.urgent-snapshot-chunk-ready-topic}"],
        groupId = "\${calculator.kafka.urgent-consumer-group-id}",
    )
    suspend fun consumeUrgent(message: String, acknowledgment: Acknowledgment) {
        val event = eventParser.parse(message)
        log.info(
            "[URGENT] received chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            event.runId, event.endpoint, event.chunkId, event.objectKey, event.recordCount,
        )
        dispatchService.dispatch(event, acknowledgment, label = "URGENT")
    }
}
