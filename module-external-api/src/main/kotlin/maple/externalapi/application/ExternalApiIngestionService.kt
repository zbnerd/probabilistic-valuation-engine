package maple.externalapi.application

import java.util.UUID
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiFetchResult
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.inbound.FetchExternalApiUseCase
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.port.out.ExternalApiClientPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ExternalApiIngestionService(
    private val clientPort: ExternalApiClientPort,
    private val artifactStorePort: ExternalApiArtifactStorePort,
) : FetchExternalApiUseCase {

    private val log = LoggerFactory.getLogger(ExternalApiIngestionService::class.java)

    override fun fetchSingle(
        provider: ExternalApiProvider,
        endpoint: ExternalApiEndpoint,
        requestKey: String,
        characterName: String?,
        ocid: String?,
    ): ExternalApiFetchResult {
        val jobId = UUID.randomUUID().toString().take(8)
        return try {
            log.info("[ExternalApi] fetch start: jobId={}, endpoint={}, key={}", jobId, endpoint, requestKey)

            val data = clientPort.fetch(provider, endpoint, requestKey).join()
            val payloadRef = artifactStorePort.store(endpoint, requestKey, data)

            val result = ExternalApiFetchResult(
                jobId = jobId,
                endpoint = endpoint,
                requestKey = requestKey,
                payloadRef = payloadRef,
                success = true,
            )
            log.info("[ExternalApi] fetch completed: jobId={}, key={}, size={}bytes", jobId, requestKey, payloadRef.sizeBytes)
            result
        } catch (ex: java.util.concurrent.CompletionException) {
            val cause = ex.cause ?: ex
            log.error("[ExternalApi] fetch failed: jobId={}, endpoint={}, key={}", jobId, endpoint, requestKey, cause)
            ExternalApiFetchResult(
                jobId = jobId,
                endpoint = endpoint,
                requestKey = requestKey,
                payloadRef = null,
                success = false,
                errorMessage = cause.message,
            )
        } catch (ex: Exception) {
            log.error("[ExternalApi] fetch failed: jobId={}, endpoint={}, key={}", jobId, endpoint, requestKey, ex)
            ExternalApiFetchResult(
                jobId = jobId,
                endpoint = endpoint,
                requestKey = requestKey,
                payloadRef = null,
                success = false,
                errorMessage = ex.message,
            )
        }
    }

    override fun fetchBatch(
        provider: ExternalApiProvider,
        endpoint: ExternalApiEndpoint,
        requestKeys: List<String>,
        characterNames: Map<String, String>,
    ): List<ExternalApiFetchResult> {
        log.info("[ExternalApi] batch start: endpoint={}, count={}", endpoint, requestKeys.size)
        return requestKeys.map { key ->
            fetchSingle(provider, endpoint, key, characterNames[key])
        }
    }
}
