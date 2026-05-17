package maple.externalapi.port.out

import java.util.concurrent.CompletableFuture
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider

interface ExternalApiClientPort {

    fun fetch(
        provider: ExternalApiProvider,
        endpoint: ExternalApiEndpoint,
        requestKey: String,
    ): CompletableFuture<ByteArray>
}
