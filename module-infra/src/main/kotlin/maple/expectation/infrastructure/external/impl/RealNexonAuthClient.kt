package maple.expectation.infrastructure.external.impl

import java.time.Duration
import java.util.Optional
import java.util.concurrent.TimeoutException
import maple.expectation.infrastructure.external.NexonAuthClient
import maple.expectation.infrastructure.external.dto.v2.CharacterListResponse
import maple.nexon.client.byok.ByokNexonClient
import maple.nexon.client.config.ByokNexonClientProperties
import maple.nexon.client.failure.DecodeFailure
import maple.nexon.client.failure.InvalidCredential
import maple.nexon.client.failure.InvalidRequest
import maple.nexon.client.failure.NotFound
import maple.nexon.client.failure.Timeout
import maple.nexon.client.failure.TimeoutKind
import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest
import maple.pipeline.messaging.contract.CompletionFailures
import org.springframework.stereotype.Component
import reactor.core.Exceptions
import reactor.core.publisher.Mono

/** Synchronous app/web compatibility facade over the typed BYOK client. */
@Component
class RealNexonAuthClient(
    private val byokNexonClient: ByokNexonClient,
    private val characterListMapper: NexonCharacterListMapper,
    private val properties: ByokNexonClientProperties,
) : NexonAuthClient {
    private val effectiveCallTimeout = Duration.ofSeconds(properties.callTimeoutSeconds)

    override fun getCharacterList(apiKey: String): Optional<CharacterListResponse> {
        val characterList = try {
            Mono.fromFuture(byokNexonClient.getCharacterList(apiKey))
                .block(effectiveCallTimeout.plusMillis(FACADE_COMPLETION_MARGIN_MS))
        } catch (failure: Throwable) {
            return terminalResultOrThrow(failure)
        } ?: throw DecodeFailure(CHARACTER_LIST_REQUEST)

        if (characterList.accounts.isEmpty()) {
            return Optional.empty()
        }
        return Optional.of(characterListMapper.toLegacy(characterList))
    }

    private fun terminalResultOrThrow(failure: Throwable): Optional<CharacterListResponse> {
        val cause = CompletionFailures.unwrap(Exceptions.unwrap(failure))
        if (failure.hasTimeoutCause() && cause !is Timeout) {
            throw Timeout(CHARACTER_LIST_REQUEST, TimeoutKind.CALL)
        }
        return when (cause) {
            is InvalidCredential,
            is NotFound,
            is InvalidRequest,
            -> Optional.empty()

            else -> throw cause
        }
    }

    private fun Throwable.hasTimeoutCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is TimeoutException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private companion object {
        private const val FACADE_COMPLETION_MARGIN_MS = 250L
        private val CHARACTER_LIST_REQUEST = NexonRequest(
            purpose = NexonEndpointPurpose.CHARACTER_LIST,
            path = "/maplestory/v1/character/list",
            query = emptyMap(),
            endpointTemplate = "/maplestory/v1/character/list",
        )
    }
}
