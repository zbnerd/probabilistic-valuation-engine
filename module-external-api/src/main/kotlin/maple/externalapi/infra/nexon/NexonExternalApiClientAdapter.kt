package maple.externalapi.infra.nexon

import io.netty.channel.ChannelOption
import java.time.Duration
import java.util.concurrent.CompletableFuture
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.domain.KeyType
import maple.externalapi.port.out.ExternalApiClientPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.DefaultUriBuilderFactory
import reactor.netty.http.client.HttpClient

@Component
class NexonExternalApiClientAdapter(
    @Value("\${nexon.api.key}")
    private val apiKey: String,
) : ExternalApiClientPort {

    private val log = LoggerFactory.getLogger(NexonExternalApiClientAdapter::class.java)

    private val webClient: WebClient by lazy {
        val factory = DefaultUriBuilderFactory("https://open.api.nexon.com")
        factory.encodingMode = DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY

        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
            .responseTimeout(Duration.ofSeconds(5))

        WebClient.builder()
            .uriBuilderFactory(factory)
            .baseUrl("https://open.api.nexon.com")
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .codecs { it.defaultCodecs().maxInMemorySize(2 * 1024 * 1024) }
            .build()
    }

    override fun fetch(
        provider: ExternalApiProvider,
        endpoint: ExternalApiEndpoint,
        requestKey: String,
    ): CompletableFuture<ByteArray> {
        val queryParam = when (endpoint.keyType) {
            KeyType.USER_IGN -> "character_name"
            KeyType.OCID -> "ocid"
        }

        return webClient.get()
            .uri { builder ->
                builder
                    .path(endpoint.path)
                    .queryParam(queryParam, requestKey)
                    .build()
            }
            .header("x-nxopen-api-key", apiKey)
            .retrieve()
            .bodyToMono(ByteArray::class.java)
            .doOnNext { log.debug("[NexonAdapter] fetch OK: endpoint={}, key={}", endpoint.name, requestKey) }
            .onErrorResume(WebClientResponseException::class.java) { ex ->
                log.warn("[NexonAdapter] fetch failed: endpoint={}, key={}, status={}, body={}", endpoint.name, requestKey, ex.statusCode, ex.responseBodyAsString)
                throw ex
            }
            .timeout(Duration.ofSeconds(10))
            .toFuture()
    }
}
