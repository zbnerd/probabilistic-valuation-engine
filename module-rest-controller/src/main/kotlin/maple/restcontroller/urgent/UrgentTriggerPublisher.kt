package maple.restcontroller.urgent

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate

class UrgentTriggerPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val topic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(request: UrgentCharacterRequest) {
        val json = objectMapper.writeValueAsString(request)
        kafkaTemplate.send(topic, request.userIgn, json)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error("Failed to publish urgent request: userIgn={}, topic={}", request.userIgn, topic, ex)
                } else {
                    log.info(
                        "Published urgent request: userIgn={}, topic={}, partition={}, offset={}",
                        request.userIgn,
                        topic,
                        result.recordMetadata.partition(),
                        result.recordMetadata.offset(),
                    )
                }
            }
    }
}
