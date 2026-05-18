package maple.calculator.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import maple.calculator.CalculatorChunkProcessingCoordinator
import maple.expectation.common.event.SnapshotChunkReadyEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class KafkaSnapshotChunkReadyConsumer(
    private val objectMapper: ObjectMapper,
    private val coordinator: CalculatorChunkProcessingCoordinator,
) {
    private val log = LoggerFactory.getLogger(KafkaSnapshotChunkReadyConsumer::class.java)

    @KafkaListener(
        topics = ["\${calculator.kafka.snapshot-chunk-ready-topic}"],
        groupId = "\${calculator.kafka.consumer-group-id}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        log.info(
            "[Consumer] received chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            event.runId, event.endpoint, event.chunkId, event.objectKey, event.recordCount,
        )
        runBlocking { coordinator.handle(event) }
        acknowledgment.acknowledge()
    }

    @KafkaListener(
        topics = ["\${calculator.kafka.urgent-snapshot-chunk-ready-topic}"],
        groupId = "\${calculator.kafka.urgent-consumer-group-id}",
    )
    fun consumeUrgent(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        log.info(
            "[Consumer] received URGENT chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            event.runId, event.endpoint, event.chunkId, event.objectKey, event.recordCount,
        )
        runBlocking { coordinator.handle(event) }
        acknowledgment.acknowledge()
    }
}
