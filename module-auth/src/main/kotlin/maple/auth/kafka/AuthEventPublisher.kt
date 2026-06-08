package maple.auth.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.auth.event.CharacterFetchRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class AuthEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${auth.kafka.character-fetch-request-topic}") private val requestTopic: String,
) {
    fun publishCharacterFetchRequest(request: CharacterFetchRequest) {
        val json = objectMapper.writeValueAsString(request)
        kafkaTemplate.send(requestTopic, request.kafkaKey(), json).whenComplete { _, ex ->
            if (ex != null) {
                log.error("[AuthEvent] failed to publish request: eventId={}", request.eventId, ex)
            } else {
                log.debug("[AuthEvent] published request: eventId={}, userIgn={}", request.eventId, request.userIgn)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AuthEventPublisher::class.java)
    }
}
