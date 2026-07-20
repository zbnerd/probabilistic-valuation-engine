package maple.nexon.client.transport

import io.netty.channel.ChannelOption
import java.net.URI
import maple.nexon.client.config.ByokNexonClientProperties
import maple.nexon.client.config.NexonClientProfile
import maple.nexon.client.config.NexonHttpClientProperties
import maple.nexon.client.config.SystemNexonClientProperties
import maple.nexon.client.config.toSettings
import maple.nexon.client.failure.NexonFailureClassifier
import maple.nexon.client.metrics.NexonClientMetrics
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.DefaultUriBuilderFactory
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider

class NexonTransportFactory(
    private val classifier: NexonFailureClassifier,
    private val metrics: NexonClientMetrics,
    private val baseUrl: String = NEXON_BASE_URL,
) {
    fun create(
        profile: NexonClientProfile,
        properties: SystemNexonClientProperties,
    ): NexonTransport {
        require(profile == NexonClientProfile.SYSTEM_BULK) { "System properties require SYSTEM_BULK profile" }
        return createTransport(properties, profile)
    }

    fun create(
        profile: NexonClientProfile,
        properties: ByokNexonClientProperties,
    ): NexonTransport {
        require(profile == NexonClientProfile.USER_BYOK) { "BYOK properties require USER_BYOK profile" }
        return createTransport(properties, profile)
    }

    private fun createTransport(
        properties: NexonHttpClientProperties,
        profile: NexonClientProfile,
    ): NexonTransport {
        val settings = properties.toSettings(profile)
        val provider = ConnectionProvider.builder(settings.poolName)
            .maxConnections(settings.maxConnections)
            .pendingAcquireMaxCount(settings.pendingAcquireMaxCount)
            .pendingAcquireTimeout(settings.pendingAcquireTimeout)
            .metrics(settings.metricsEnabled)
            .build()
        val httpClient = HttpClient.create(provider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, settings.connectTimeoutMs)
            .responseTimeout(settings.responseTimeout)
            .compress(true)
            .metrics(settings.metricsEnabled, ::normalizeMetricUri)
        val uriFactory = DefaultUriBuilderFactory(baseUrl).apply {
            encodingMode = DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY
        }
        val webClient = WebClient.builder()
            .uriBuilderFactory(uriFactory)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .codecs { codecs -> codecs.defaultCodecs().maxInMemorySize(settings.maxInMemorySizeBytes) }
            .build()
        return NexonTransport(settings, provider, webClient, classifier, metrics)
    }

    internal fun normalizeMetricUri(rawUri: String): String {
        val withoutQuery = rawUri.substringBefore('?').substringBefore('#')
        val path = if (withoutQuery.startsWith("http://") || withoutQuery.startsWith("https://")) {
            runCatching { URI.create(withoutQuery).path }.getOrNull()
        } else {
            withoutQuery
        }
        return path?.takeIf { it.startsWith('/') && it.length <= MAX_METRIC_URI_LENGTH } ?: UNKNOWN_ENDPOINT
    }

    private companion object {
        private const val NEXON_BASE_URL = "https://open.api.nexon.com"
        private const val UNKNOWN_ENDPOINT = "/unknown"
        private const val MAX_METRIC_URI_LENGTH = 160
    }
}
