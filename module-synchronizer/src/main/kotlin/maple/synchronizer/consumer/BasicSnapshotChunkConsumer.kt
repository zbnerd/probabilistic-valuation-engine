package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.synchronizer.service.BasicChunkIngestionService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

@Component
class BasicSnapshotChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val ingestionService: BasicChunkIngestionService,
) {
    @KafkaListener(
        topics = ["\${synchronizer.kafka.basic-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.basic-consumer-group-id}",
    )
    fun consume(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) messageKey: String?,
    ) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        if (!ingestionService.process(event, message, acknowledgment, topic, messageKey, urgent = false)) {
            acknowledgment.acknowledge()
        }
    }

    @KafkaListener(
        topics = ["\${synchronizer.kafka.urgent-basic-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.urgent-basic-consumer-group-id}",
    )
    fun consumeUrgentBasic(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) messageKey: String?,
    ) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        if (!ingestionService.process(event, message, acknowledgment, topic, messageKey, urgent = true)) {
            acknowledgment.acknowledge()
        }
    }
}
