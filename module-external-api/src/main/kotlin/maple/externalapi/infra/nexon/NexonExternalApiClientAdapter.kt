package maple.externalapi.infra.nexon

import java.util.concurrent.CompletableFuture
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Stub adapter — returns dummy JSON.
 * Next PR: delegate to existing NexonApiClient after moving interface to module-core.
 */
@Component
class NexonExternalApiClientAdapter : ExternalApiClientPort {

    private val log = LoggerFactory.getLogger(NexonExternalApiClientAdapter::class.java)

    override fun fetch(
        provider: ExternalApiProvider,
        endpoint: ExternalApiEndpoint,
        requestKey: String,
    ): CompletableFuture<ByteArray> {
        log.info("[NexonAdapter:STUB] fetch called: endpoint={}, key={}", endpoint, requestKey)
        val dummyJson = """{"endpoint":"${endpoint.path}","key":"$requestKey","status":"stub"}"""
        return CompletableFuture.completedFuture(dummyJson.toByteArray())
    }
}
