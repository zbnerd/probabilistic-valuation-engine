package maple.externalapi.auth

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import maple.expectation.core.auth.event.CharacterFetchRequest
import maple.expectation.core.auth.event.CharacterFetchResponse
import maple.nexon.client.byok.ByokNexonClient
import maple.nexon.client.byok.NexonCharacterList
import maple.nexon.client.failure.DecodeFailure
import maple.nexon.client.failure.InvalidCredential
import maple.nexon.client.failure.InvalidRequest
import maple.nexon.client.failure.NotFound
import maple.nexon.client.failure.RateLimited
import maple.nexon.client.failure.ResponseTooLarge
import maple.nexon.client.failure.Timeout
import maple.nexon.client.failure.UpstreamUnavailable
import maple.pipeline.messaging.contract.CompletionFailures
import maple.pipeline.messaging.contract.DeliveryOutcome
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class AuthCharacterFetchHandler(
    private val byokNexonClient: ByokNexonClient,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${auth.kafka.character-fetch-response-topic}") private val responseTopic: String,
) {
    fun handle(
        message: String,
        @Suppress("UNUSED_PARAMETER") messageKey: String?,
    ): CompletionStage<DeliveryOutcome> {
        val request = runCatching {
            objectMapper.readValue(message, CharacterFetchRequest::class.java)
        }.getOrElse {
            return CompletableFuture.completedFuture(DeliveryOutcome.InvalidMessage(INVALID_MESSAGE))
        }

        val fetch = runCatching {
            byokNexonClient.getCharacterList(request.apiKey)
        }.getOrElse(CompletableFuture<NexonCharacterList>::failedFuture)

        return fetch.handle { characterList, failure ->
            if (failure == null) {
                Decision.Publish(successResponse(request, requireNotNull(characterList)))
            } else {
                decisionForFailure(request, CompletionFailures.unwrap(failure))
            }
        }.thenCompose(::complete)
    }

    private fun decisionForFailure(request: CharacterFetchRequest, failure: Throwable): Decision = when (failure) {
        is InvalidCredential -> Decision.Publish(errorResponse(request, INVALID_API_KEY))
        is NotFound -> Decision.Publish(errorResponse(request, NO_ACCESSIBLE_CHARACTERS))
        is InvalidRequest -> Decision.Complete(DeliveryOutcome.InvalidMessage(INVALID_NEXON_REQUEST))
        is RateLimited,
        is Timeout,
        is UpstreamUnavailable,
        is ResponseTooLarge,
        is DecodeFailure,
        -> Decision.Complete(DeliveryOutcome.Retryable(failure))

        else -> Decision.Complete(DeliveryOutcome.Retryable(failure))
    }

    private fun complete(decision: Decision): CompletionStage<DeliveryOutcome> = when (decision) {
        is Decision.Complete -> CompletableFuture.completedFuture(decision.outcome)
        is Decision.Publish -> publishResponse(decision.response)
    }

    private fun successResponse(
        request: CharacterFetchRequest,
        characterList: NexonCharacterList,
    ): CharacterFetchResponse {
        val characterOcidMap = characterList.characters.mapNotNull { character ->
            val name = character.characterName
            val ocid = character.ocid
            if (name.isNullOrBlank() || ocid.isNullOrBlank()) null else name to ocid
        }.toMap()
        return CharacterFetchResponse(
            eventId = request.eventId,
            accountId = characterList.accounts.firstOrNull()?.accountId,
            success = true,
            characterOcidMap = characterOcidMap,
        )
    }

    private fun errorResponse(request: CharacterFetchRequest, errorMessage: String): CharacterFetchResponse = CharacterFetchResponse(
        eventId = request.eventId,
        success = false,
        errorMessage = errorMessage,
    )

    private fun publishResponse(response: CharacterFetchResponse): CompletionStage<DeliveryOutcome> {
        val publish = runCatching {
            val json = objectMapper.writeValueAsString(response)
            kafkaTemplate.send(responseTopic, response.kafkaKey(), json).thenApply { null }
        }.getOrElse(CompletableFuture<Void>::failedFuture)

        return publish.handle { _, failure ->
            if (failure == null) {
                DeliveryOutcome.Success
            } else {
                DeliveryOutcome.Retryable(CompletionFailures.unwrap(failure))
            }
        }
    }

    private sealed interface Decision {
        data class Publish(val response: CharacterFetchResponse) : Decision

        data class Complete(val outcome: DeliveryOutcome) : Decision
    }

    private companion object {
        private const val INVALID_MESSAGE = "INVALID_MESSAGE"
        private const val INVALID_NEXON_REQUEST = "INVALID_NEXON_REQUEST"
        private const val INVALID_API_KEY = "Invalid API key or Nexon API error (OPENAPI00004)"
        private const val NO_ACCESSIBLE_CHARACTERS = "No accessible characters found"
    }
}
