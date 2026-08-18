package maple.nexon.client.transport

import java.time.Duration
import java.util.concurrent.CompletableFuture
import maple.nexon.client.config.NexonTransportSettings
import maple.nexon.client.failure.NexonFailure
import maple.nexon.client.failure.NexonFailureClassifier
import maple.nexon.client.metrics.NexonClientMetrics
import maple.nexon.client.model.NexonRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.resources.ConnectionProvider

class NexonTransport internal constructor(
    val settings: NexonTransportSettings,
    val provider: ConnectionProvider,
    internal val webClient: WebClient,
    private val failureClassifier: NexonFailureClassifier,
    private val metrics: NexonClientMetrics,
) {
    fun exchange(request: NexonRequest, apiKey: String): CompletableFuture<ByteArray> {
        val startedAt = System.nanoTime()
        return webClient.get()
            .uri { builder ->
                var requestBuilder = builder.path(request.path)
                val queryVariables = LinkedHashMap<String, String>()
                request.query.entries.forEachIndexed { index, (name, value) ->
                    val variableName = "nexonQuery$index"
                    requestBuilder = requestBuilder.queryParam(name, "{$variableName}")
                    queryVariables[variableName] = value
                }
                requestBuilder.build(queryVariables)
            }
            .header(API_KEY_HEADER, apiKey)
            .exchangeToMono { response -> responseBody(response, request) }
            .timeout(settings.callTimeout)
            .onErrorMap { failure -> classify(request, failure) }
            .doOnSuccess { body ->
                metrics.recordSuccess(settings.profile, request, elapsed(startedAt), body.size)
            }
            .doOnError(NexonFailure::class.java) { failure ->
                metrics.recordFailure(settings.profile, request, elapsed(startedAt), failure)
            }
            .toFuture()
    }

    private fun responseBody(response: ClientResponse, request: NexonRequest): Mono<ByteArray> {
        val body = response.bodyToMono(ByteArray::class.java).defaultIfEmpty(ByteArray(0))
        if (response.statusCode().is2xxSuccessful) {
            return body
        }
        return body.flatMap { bytes ->
            Mono.error(
                failureClassifier.classifyHttp(
                    request = request,
                    status = response.statusCode().value(),
                    errorBody = bytes,
                    retryAfter = response.headers().asHttpHeaders().getFirst("Retry-After")
                        ?.toLongOrNull()
                        ?.takeIf { it >= 0 }
                        ?.let(Duration::ofSeconds),
                ),
            )
        }
    }

    private fun classify(request: NexonRequest, failure: Throwable): Throwable = if (failure is NexonFailure) failure else failureClassifier.classifyTransport(request, failure)

    private fun elapsed(startedAt: Long): Duration = Duration.ofNanos((System.nanoTime() - startedAt).coerceAtLeast(0))

    private companion object {
        private const val API_KEY_HEADER = "x-nxopen-api-key"
    }
}
