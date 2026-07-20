package maple.expectation.infrastructure.config

import maple.nexon.client.config.NexonClientAutoConfiguration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.reactive.function.client.WebClient

/** App/web compatibility facade over the shared Nexon client configuration. */
@Configuration(proxyBeanMethods = false)
@Import(NexonClientAutoConfiguration::class)
@EnableConfigurationProperties(NexonApiProperties::class)
class MaplestoryApiConfig {
    @Bean("mapleWebClient")
    fun mapleWebClient(
        @Qualifier("nexonSystemWebClient") systemWebClient: WebClient,
    ): WebClient = systemWebClient
}
