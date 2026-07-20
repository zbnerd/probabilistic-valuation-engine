package maple.externalapi.auth

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import maple.expectation.core.auth.event.CharacterFetchRequest
import maple.expectation.core.auth.event.CharacterFetchResponse
import maple.expectation.infrastructure.external.NexonAuthClient
import maple.pipeline.messaging.contract.CompletionFailures
import maple.pipeline.messaging.contract.DeliveryOutcome
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class AuthCharacterFetchConsumer(
    private val nexonAuthClient: NexonAuthClient,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${auth.kafka.character-fetch-response-topic}") private val responseTopic: String,
    @Qualifier("authCharacterFetchExecutor") private val executor: Executor,
) {
    fun consume(
        message: String,
        messageKey: String?,
    ): CompletionStage<DeliveryOutcome> {
        val request = runCatching {
            objectMapper.readValue(message, CharacterFetchRequest::class.java)
        }.getOrElse {
            return CompletableFuture.completedFuture(DeliveryOutcome.InvalidMessage(INVALID_MESSAGE))
        }
        log.info("[AuthFetch] processing: eventId={}, userIgn={}", request.eventId, request.userIgn)

        val response = runCatching {
            CompletableFuture.supplyAsync({ responseFor(request) }, executor)
        }.getOrElse { failure ->
            return CompletableFuture.completedFuture(DeliveryOutcome.Retryable(failure))
        }
        return response.thenCompose(::publishResponse).handle { _, failure ->
            if (failure == null) {
                DeliveryOutcome.Success
            } else {
                DeliveryOutcome.Retryable(CompletionFailures.unwrap(failure))
            }
        }
    }

    private fun responseFor(request: CharacterFetchRequest): CharacterFetchResponse = runCatching {
        val characterList = nexonAuthClient.getCharacterList(request.apiKey)
        if (characterList.isEmpty) {
            return@runCatching errorResponse(request, INVALID_API_KEY)
        }
        val response = characterList.orElseThrow()
        val accountId = response.accountList?.firstOrNull()?.accountId
        val characterOcidMap = response.getAllCharacters().mapNotNull { character ->
            val name = character.characterName
            val ocid = character.ocid
            if (name.isNullOrBlank() || ocid.isNullOrBlank()) null else name to ocid
        }.toMap()
        log.info(
            "[AuthFetch] completed: eventId={}, accountId={}, resolved={}",
            request.eventId,
            accountId,
            characterOcidMap.size,
        )
        CharacterFetchResponse(
            eventId = request.eventId,
            accountId = accountId,
            success = true,
            characterOcidMap = characterOcidMap,
        )
    }.getOrElse { failure ->
        log.error(
            "[AuthFetch] failed: eventId={}, failureType={}",
            request.eventId,
            failure.javaClass.simpleName,
        )
        errorResponse(request, INTERNAL_ERROR)
    }

    private fun errorResponse(request: CharacterFetchRequest, errorMessage: String): CharacterFetchResponse {
        log.warn("[AuthFetch] error: eventId={}, error={}", request.eventId, errorMessage)
        return CharacterFetchResponse(
            eventId = request.eventId,
            success = false,
            errorMessage = errorMessage,
        )
    }

    private fun publishResponse(response: CharacterFetchResponse): CompletableFuture<Void> = CompletableFuture.supplyAsync(
        { objectMapper.writeValueAsString(response) },
        executor,
    ).thenCompose { json ->
        kafkaTemplate.send(responseTopic, response.kafkaKey(), json).thenApply { null }
    }

    companion object {
        private const val INVALID_MESSAGE = "INVALID_MESSAGE"
        private const val INVALID_API_KEY = "Invalid API key or Nexon API error (OPENAPI00004)"
        private const val INTERNAL_ERROR = "Internal error"
        private val log = LoggerFactory.getLogger(AuthCharacterFetchConsumer::class.java)
    }
}
