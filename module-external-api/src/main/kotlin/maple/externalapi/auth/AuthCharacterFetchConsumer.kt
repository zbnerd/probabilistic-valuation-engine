package maple.externalapi.auth

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
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

    @PreDestroy
    fun shutdown() {
        vtExecutor.shutdown()
        if (!vtExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
            log.warn("[AuthFetch] VT executor did not terminate in 5s")
            vtExecutor.shutdownNow()
        }
        log.info("[AuthFetch] VT executor shut down")
    }

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
        log.info("[AuthFetch] processing: eventId={}, userIgn={}", request.eventId, request.userIgn)

        vtExecutor.submit {
            runCatching {
                val characterListOpt = nexonAuthClient.getCharacterList(request.apiKey)

                if (characterListOpt.isEmpty) {
                    publishError(request, "Invalid API key or Nexon API error (OPENAPI00004)")
                    return@submit
                }

                val resp = characterListOpt.get()
                val accountId = resp.accountList?.firstOrNull()?.accountId
                val allCharacters = resp.getAllCharacters()

                val characterOcidMap = mutableMapOf<String, String>()
                for (char in allCharacters) {
                    val name = char.characterName
                    val ocid = char.ocid
                    if (!name.isNullOrBlank() && !ocid.isNullOrBlank()) {
                        characterOcidMap[name] = ocid
                    }
                }

                publishSuccess(request, accountId, characterOcidMap)
                log.info("[AuthFetch] completed: eventId={}, accountId={}, resolved={}", request.eventId, accountId, characterOcidMap.size)
            }.onFailure { ex ->
                log.error("[AuthFetch] failed: eventId={}", request.eventId, ex)
                publishError(request, "Internal error: ${ex.message}")
            }

            runCatching { acknowledgment.acknowledge() }
                .onFailure { log.warn("[AuthFetch] ACK failed: eventId={}", request.eventId) }
        }
    }

    private fun publishSuccess(request: CharacterFetchRequest, accountId: String?, characterOcidMap: Map<String, String>) {
        val response = CharacterFetchResponse(
            eventId = request.eventId,
            accountId = accountId,
            success = true,
            characterOcidMap = characterOcidMap,
        )
        publishResponse(response)
    }

    private fun publishError(request: CharacterFetchRequest, errorMessage: String) {
        log.warn("[AuthFetch] error: eventId={}, error={}", request.eventId, errorMessage)
        val response = CharacterFetchResponse(
            eventId = request.eventId,
            success = false,
            errorMessage = errorMessage,
        )
        publishResponse(response)
    }

    private fun publishResponse(response: CharacterFetchResponse) {
        val json = objectMapper.writeValueAsString(response)
        kafkaTemplate.send(responseTopic, response.kafkaKey(), json).whenComplete { _, ex ->
            if (ex != null) {
                log.error("[AuthFetch] failed to publish response: eventId={}", response.eventId, ex)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AuthCharacterFetchConsumer::class.java)
    }
}
