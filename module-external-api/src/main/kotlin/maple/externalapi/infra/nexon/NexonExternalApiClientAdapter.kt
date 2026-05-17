package maple.externalapi.infra.nexon

import io.netty.channel.ChannelOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import maple.externalapi.config.NexonHttpClientProperties
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.domain.KeyType
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.DefaultUriBuilderFactory
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider

@Component
class NexonExternalApiClientAdapter(
    @Value("\${nexon.api.key}")
    private val apiKey: String,
    private val properties: NexonHttpClientProperties,
    private val fetchMetrics: SnapshotFetchMetrics,
) : ExternalApiClientPort {

    private val log = LoggerFactory.getLogger(NexonExternalApiClientAdapter::class.java)

    private val webClient: WebClient by lazy {
        val factory = DefaultUriBuilderFactory("https://open.api.nexon.com")
        factory.encodingMode = DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY

        val provider = ConnectionProvider.builder(properties.poolName)
            .maxConnections(properties.maxConnections)
            .pendingAcquireMaxCount(properties.pendingAcquireMaxCount)
            .pendingAcquireTimeout(Duration.ofMillis(properties.pendingAcquireTimeoutMs))
            .metrics(properties.metricsEnabled)
            .build()

        log.info(
            "[NexonAdapter] http client pool configured: name={}, maxConnections={}, pendingAcquireMaxCount={}, pendingAcquireTimeoutMs={}, connectTimeoutMs={}, responseTimeoutSeconds={}, metricsEnabled={}",
            properties.poolName,
            properties.maxConnections,
            properties.pendingAcquireMaxCount,
            properties.pendingAcquireTimeoutMs,
            properties.connectTimeoutMs,
            properties.responseTimeoutSeconds,
            properties.metricsEnabled,
        )

        val httpClient = HttpClient.create(provider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMs)
            .responseTimeout(Duration.ofSeconds(properties.responseTimeoutSeconds))
            .metrics(properties.metricsEnabled) { uri -> uri }

        WebClient.builder()
            .uriBuilderFactory(factory)
            .baseUrl("https://open.api.nexon.com")
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .codecs { it.defaultCodecs().maxInMemorySize(properties.maxInMemorySizeBytes) }
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

        val startedAt = Instant.now()
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
            .doOnNext { bodyBytes ->
                val elapsed = Duration.between(startedAt, Instant.now())
                fetchMetrics.recordNexonBodyReceived(endpoint.name, elapsed, bodyBytes.size)
                log.debug(
                    "[NexonAdapter] body received: endpoint={}, key={}, bytes={}, durationMs={}",
                    endpoint.name,
                    requestKey,
                    bodyBytes.size,
                    elapsed.toMillis(),
                )
            }
            .doOnError { ex ->
                if (ex !is WebClientResponseException) {
                    val elapsed = Duration.between(startedAt, Instant.now())
                    fetchMetrics.recordNexonFailure(endpoint.name, elapsed)
                    log.warn(
                        "[NexonAdapter] fetch failed: endpoint={}, key={}, durationMs={}, error={}",
                        endpoint.name,
                        requestKey,
                        elapsed.toMillis(),
                        ex.message,
                    )
                }
            }
            .onErrorResume(WebClientResponseException::class.java) { ex ->
                fetchMetrics.recordNexonFailure(endpoint.name, Duration.between(startedAt, Instant.now()))
                log.warn("[NexonAdapter] fetch failed: endpoint={}, key={}, status={}, body={}", endpoint.name, requestKey, ex.statusCode, ex.responseBodyAsString)
                throw ex
            }
            .timeout(Duration.ofSeconds(10))
            .toFuture()
    }
}
