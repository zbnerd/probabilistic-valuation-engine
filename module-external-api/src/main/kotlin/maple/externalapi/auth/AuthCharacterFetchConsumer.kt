package maple.externalapi.auth

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.auth.event.CharacterFetchResponse
import maple.expectation.core.auth.event.CharacterFetchRequest
import maple.expectation.infrastructure.external.NexonAuthClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

@Component
class AuthCharacterFetchConsumer(
    private val nexonAuthClient: NexonAuthClient,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${auth.kafka.character-fetch-response-topic}") private val responseTopic: String,
) {
    private val vtExecutor = Executors.newVirtualThreadPerTaskExecutor()

    @KafkaListener(
        topics = ["\${auth.kafka.character-fetch-request-topic}"],
        groupId = "\${auth.kafka.request-consumer-group-id}",
    )
    fun consume(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(KafkaHeaders.RECEIVED_KEY) messageKey: String?,
    ) {
        val request = objectMapper.readValue(message, CharacterFetchRequest::class.java)
        log.info("[AuthFetch] processing: fingerprint={}, userIgn={}", request.fingerprint, request.userIgn)

        vtExecutor.submit {
            runCatching {
                val characterListOpt = nexonAuthClient.getCharacterList(request.apiKey)

                if (characterListOpt.isEmpty) {
                    publishError(request, "Invalid API key or Nexon API error (OPENAPI00004)")
                    return@submit
                }

                val allCharacters = characterListOpt.get().getAllCharacters()

                val characterOcidMap = mutableMapOf<String, String>()
                for (char in allCharacters) {
                    val name = char.characterName
                    val ocid = char.ocid
                    if (!name.isNullOrBlank() && !ocid.isNullOrBlank()) {
                        characterOcidMap[name] = ocid
                    }
                }

                // TODO: Write JSONL.gz chunk + publish SnapshotChunkReadyEvent for Synchronizer
                // runId = "auth-${request.fingerprint}", endpoint = "auth-character"
                // Synchronizer will upsert game_character with fingerprint

                publishSuccess(request, characterOcidMap)
                log.info("[AuthFetch] completed: fingerprint={}, resolved={}", request.fingerprint, characterOcidMap.size)
            }.onFailure { ex ->
                log.error("[AuthFetch] failed: fingerprint={}", request.fingerprint, ex)
                publishError(request, "Internal error: ${ex.message}")
            }
        }

        acknowledgment.acknowledge()
    }

    private fun publishSuccess(request: CharacterFetchRequest, characterOcidMap: Map<String, String>) {
        val response = CharacterFetchResponse(
            eventId = request.eventId,
            fingerprint = request.fingerprint,
            success = true,
            characterOcidMap = characterOcidMap,
        )
        publishResponse(response)
    }

    private fun publishError(request: CharacterFetchRequest, errorMessage: String) {
        log.warn("[AuthFetch] error: fingerprint={}, error={}", request.fingerprint, errorMessage)
        val response = CharacterFetchResponse(
            eventId = request.eventId,
            fingerprint = request.fingerprint,
            success = false,
            errorMessage = errorMessage,
        )
        publishResponse(response)
    }

    private fun publishResponse(response: CharacterFetchResponse) {
        val json = objectMapper.writeValueAsString(response)
        kafkaTemplate.send(responseTopic, response.kafkaKey(), json).whenComplete { _, ex ->
            if (ex != null) {
                log.error("[AuthFetch] failed to publish response: fingerprint={}", response.fingerprint, ex)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AuthCharacterFetchConsumer::class.java)
    }
}
