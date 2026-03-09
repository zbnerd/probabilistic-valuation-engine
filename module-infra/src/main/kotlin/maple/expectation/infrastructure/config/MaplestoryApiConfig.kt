package maple.expectation.infrastructure.config

import io.netty.channel.ChannelOption
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.DefaultUriBuilderFactory
import reactor.netty.http.client.HttpClient

/**
 * Nexon Open API WebClient 설정
 *
 * **타임아웃 계층:**
 * * connectTimeout: TCP 연결 타임아웃 (기본 3초)
 * * responseTimeout: 응답 수신 타임아웃 (기본 5초)
 *
 * @see NexonApiProperties 타임아웃 설정 프로퍼티
 */
@Configuration
@EnableConfigurationProperties(NexonApiProperties::class)
class MaplestoryApiConfig(
    private val properties: NexonApiProperties,
) {

    @Bean("mapleWebClient")
    fun mapleWebClient(): WebClient {
        // URI 인코딩 모드 설정 (한글 깨짐 방지)
        val factory = DefaultUriBuilderFactory("https://open.api.nexon.com")
        factory.encodingMode = DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY

        // HttpClient with timeouts and compression
        val httpClient = HttpClient.create()
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                properties.connectTimeout.toMillis().toInt(),
            )
            .responseTimeout(properties.responseTimeout)
            .compress(true)

        return WebClient.builder()
            .uriBuilderFactory(factory)
            .baseUrl("https://open.api.nexon.com")
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
