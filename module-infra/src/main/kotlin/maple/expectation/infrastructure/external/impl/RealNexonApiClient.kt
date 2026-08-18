package maple.expectation.infrastructure.external.impl

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import maple.expectation.error.exception.CharacterNotFoundException
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.dto.v2.CharacterBasicResponse
import maple.expectation.infrastructure.external.dto.v2.CharacterOcidResponse
import maple.expectation.infrastructure.external.dto.v2.CubeHistoryResponse
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.nexon.client.failure.DecodeFailure
import maple.nexon.client.failure.NotFound
import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest
import maple.nexon.client.system.SystemKeyNexonClient
import maple.pipeline.messaging.contract.CompletionFailures
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** App/web compatibility adapter over the shared system-key Nexon client. */
@Profile("!chaos")
@Component("realNexonApiClient")
@org.springframework.beans.factory.annotation.Qualifier("realNexonApiClient")
class RealNexonApiClient(
    private val systemKeyNexonClient: SystemKeyNexonClient,
    private val objectMapper: ObjectMapper,
    @Value("\${nexon.api.key}") private val apiKey: String,
) : NexonApiClient {
    override fun getOcidByCharacterName(characterName: String): CompletableFuture<CharacterOcidResponse> {
        val request = request(
            purpose = NexonEndpointPurpose.OCID_LOOKUP,
            path = "/maplestory/v1/id",
            queryName = "character_name",
            queryValue = characterName,
        )
        return fetch(request, CharacterOcidResponse::class.java, characterName)
    }

    override fun getCharacterBasic(ocid: String): CompletableFuture<CharacterBasicResponse> {
        val request = request(
            purpose = NexonEndpointPurpose.CHARACTER_BASIC,
            path = "/maplestory/v1/character/basic",
            queryName = "ocid",
            queryValue = ocid,
        )
        return fetch(request, CharacterBasicResponse::class.java)
    }

    override fun getItemDataByOcid(ocid: String): CompletableFuture<EquipmentResponse> {
        val request = request(
            purpose = NexonEndpointPurpose.ITEM_EQUIPMENT,
            path = "/maplestory/v1/character/item-equipment",
            queryName = "ocid",
            queryValue = ocid,
        )
        return fetch(request, EquipmentResponse::class.java)
    }

    override fun getCubeHistory(ocid: String): CompletableFuture<CubeHistoryResponse> {
        val request = request(
            purpose = NexonEndpointPurpose.CUBE_HISTORY,
            path = "/maplestory/v1/history/cube",
            queryName = "ocid",
            queryValue = ocid,
        )
        return fetch(request, CubeHistoryResponse::class.java)
    }

    private fun <T : Any> fetch(
        request: NexonRequest,
        responseType: Class<T>,
        characterNameForNotFound: String? = null,
    ): CompletableFuture<T> = systemKeyNexonClient.fetch(request, apiKey).handle { body, failure ->
        if (failure != null) {
            val cause = CompletionFailures.unwrap(failure)
            if (cause is NotFound && characterNameForNotFound != null) {
                throw CharacterNotFoundException(characterNameForNotFound)
            }
            throw cause
        }
        try {
            objectMapper.readValue(requireNotNull(body), responseType)
        } catch (_: Exception) {
            throw DecodeFailure(request)
        }
    }

    private fun request(
        purpose: NexonEndpointPurpose,
        path: String,
        queryName: String,
        queryValue: String,
    ): NexonRequest = NexonRequest(
        purpose = purpose,
        path = path,
        query = mapOf(queryName to queryValue),
        endpointTemplate = path,
    )
}
