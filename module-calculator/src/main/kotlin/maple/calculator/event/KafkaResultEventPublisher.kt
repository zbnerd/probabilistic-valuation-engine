package maple.calculator.event

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.CalculatorResultChunkReadyEvent
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class KafkaResultEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${calculator.kafka.result-chunk-ready-topic}")
    private val resultChunkReadyTopic: String,
) {
    private val log = LoggerFactory.getLogger(KafkaResultEventPublisher::class.java)

    suspend fun publishChunkReady(event: CalculatorResultChunkReadyEvent) {
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(resultChunkReadyTopic, event.kafkaKey(), payload).await()
        log.info(
            "[Event] published calculator result chunk-ready: sourceRunId={} sourceEndpoint={} sourceChunkId={} objectKey={} results={}",
            event.sourceRunId,
            event.sourceEndpoint,
            event.sourceChunkId,
            event.objectKey,
            event.resultCount,
        )
    }
}
