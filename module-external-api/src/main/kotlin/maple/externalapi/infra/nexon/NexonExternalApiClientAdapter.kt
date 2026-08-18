package maple.externalapi.infra.nexon

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.domain.KeyType
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest
import maple.nexon.client.system.SystemKeyNexonClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class NexonExternalApiClientAdapter(
    @Value("\${nexon.api.key}")
    private val apiKey: String,
    private val systemClient: SystemKeyNexonClient,
    private val fetchMetrics: SnapshotFetchMetrics,
    private val clock: Clock = Clock.systemUTC(),
) : ExternalApiClientPort {
    override fun fetch(
        provider: ExternalApiProvider,
        endpoint: ExternalApiEndpoint,
        requestKey: String,
    ): CompletableFuture<ByteArray> {
        val request = when (provider) {
            ExternalApiProvider.NEXON -> requestFor(endpoint, requestKey)
        }
        val startedAt = Instant.now(clock)
        val completion = systemClient.fetch(request, apiKey)
        completion.whenComplete { body, failure ->
            val duration = Duration.between(startedAt, Instant.now(clock))
            if (failure == null && body != null) {
                fetchMetrics.recordNexonBodyReceived(endpoint.name, duration, body.size)
            } else {
                fetchMetrics.recordNexonFailure(endpoint.name, duration)
            }
        }
        return completion
    }

    private fun requestFor(endpoint: ExternalApiEndpoint, requestKey: String): NexonRequest {
        val query = when (endpoint.keyType) {
            KeyType.USER_IGN -> mapOf("character_name" to requestKey)
            KeyType.OCID -> mapOf("ocid" to requestKey)
            KeyType.DATE_PAGE -> {
                val parts = requestKey.split(":", limit = 2)
                linkedMapOf(
                    "date" to parts[0],
                    "page" to parts.getOrElse(1) { "1" },
                )
            }
        }
        return NexonRequest(
            purpose = endpoint.purpose(),
            path = endpoint.path,
            query = query,
            endpointTemplate = endpoint.path,
        )
    }

    private fun ExternalApiEndpoint.purpose(): NexonEndpointPurpose = when (this) {
        ExternalApiEndpoint.OCID_LOOKUP -> NexonEndpointPurpose.OCID_LOOKUP
        ExternalApiEndpoint.CHARACTER_BASIC -> NexonEndpointPurpose.CHARACTER_BASIC
        ExternalApiEndpoint.ITEM_EQUIPMENT -> NexonEndpointPurpose.ITEM_EQUIPMENT
        ExternalApiEndpoint.RANKING_OVERALL -> NexonEndpointPurpose.RANKING_OVERALL
    }
}
