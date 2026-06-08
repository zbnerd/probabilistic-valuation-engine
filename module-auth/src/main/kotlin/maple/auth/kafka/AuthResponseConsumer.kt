package maple.auth.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.auth.event.CharacterFetchResponse
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

@Component
class AuthResponseConsumer(
    private val objectMapper: ObjectMapper,
    private val pendingLoginRegistry: PendingLoginRegistry,
) {
    @KafkaListener(
        topics = ["\${auth.kafka.character-fetch-response-topic}"],
        groupId = "\${auth.kafka.response-consumer-group-id}",
    )
    fun consume(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(KafkaHeaders.RECEIVED_KEY) messageKey: String?,
    ) {
        val response = objectMapper.readValue(message, CharacterFetchResponse::class.java)
        log.debug("[AuthResponse] received: eventId={}, success={}, characters={}",
            response.eventId, response.success, response.characterOcidMap.size)
        pendingLoginRegistry.complete(response)
        acknowledgment.acknowledge()
    }

    companion object {
        private val log = LoggerFactory.getLogger(AuthResponseConsumer::class.java)
    }
}
