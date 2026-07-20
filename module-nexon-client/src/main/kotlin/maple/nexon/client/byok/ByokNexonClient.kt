package maple.nexon.client.byok

import java.util.concurrent.CompletableFuture
import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest
import maple.nexon.client.transport.NexonTransport

internal val CHARACTER_LIST_REQUEST = NexonRequest(
    purpose = NexonEndpointPurpose.CHARACTER_LIST,
    path = "/maplestory/v1/character/list",
    query = emptyMap(),
    endpointTemplate = "/maplestory/v1/character/list",
)

class ByokNexonClient(
    private val transport: NexonTransport,
    private val decoder: CharacterListDecoder,
) {
    fun getCharacterList(apiKey: String): CompletableFuture<NexonCharacterList> = transport.exchange(CHARACTER_LIST_REQUEST, apiKey)
        .thenApply(decoder::decode)
}
