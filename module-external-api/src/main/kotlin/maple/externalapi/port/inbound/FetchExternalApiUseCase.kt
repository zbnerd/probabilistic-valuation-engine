package maple.externalapi.port.inbound

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiFetchResult
import maple.externalapi.domain.ExternalApiProvider

interface FetchExternalApiUseCase {

    fun fetchSingle(
        provider: ExternalApiProvider,
        endpoint: ExternalApiEndpoint,
        requestKey: String,
        characterName: String? = null,
        ocid: String? = null,
    ): ExternalApiFetchResult

    fun fetchBatch(
        provider: ExternalApiProvider,
        endpoint: ExternalApiEndpoint,
        requestKeys: List<String>,
        characterNames: Map<String, String> = emptyMap(),
    ): List<ExternalApiFetchResult>
}
