package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import maple.synchronizer.event.CalculatorResultChunkReadyEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class KafkaResultChunkConsumer(
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(KafkaResultChunkConsumer::class.java)

    @KafkaListener(
        topics = ["\${synchronizer.kafka.result-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.consumer-group-id}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, CalculatorResultChunkReadyEvent::class.java)
        log.info(
            "[Synchronizer] received result chunk-ready: runId={} endpoint={} chunkId={} objectKey={} results={}",
            event.sourceRunId,
            event.sourceEndpoint,
            event.sourceChunkId,
            event.objectKey,
            event.resultCount,
        )

        // TODO: read result file from objectKey, parse, bulk insert to DB

        acknowledgment.acknowledge()
    }
}
