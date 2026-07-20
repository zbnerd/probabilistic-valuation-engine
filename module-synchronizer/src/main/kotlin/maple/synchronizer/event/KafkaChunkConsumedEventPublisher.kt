package maple.synchronizer.event

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import maple.expectation.common.event.ChunkConsumedEvent
import org.apache.kafka.clients.producer.ProducerRecord
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
    fun publish(event: ChunkConsumedEvent): CompletableFuture<Void> {
        val payload = objectMapper.writeValueAsString(event)
        return kafkaTemplate.send(ProducerRecord(topic, event.kafkaKey(), payload))
            .thenApply { null }
    }
}
