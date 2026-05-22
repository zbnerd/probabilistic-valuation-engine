package maple.synchronizer.event

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.ChunkConsumedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["synchronizer.events.consumed.enabled"], havingValue = "true", matchIfMissing = true)
class KafkaChunkConsumedEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${synchronizer.kafka.chunk-consumed-topic}")
    private val topic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(event: ChunkConsumedEvent) {
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(topic, event.kafkaKey(), payload)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("[ConsumedEvent] publish failed: runId={} chunkId={} - {}", event.runId, event.chunkId, ex.message)
                } else {
                    log.debug("[ConsumedEvent] published: runId={} chunkId={}", event.runId, event.chunkId)
                }
            }
    }
}
