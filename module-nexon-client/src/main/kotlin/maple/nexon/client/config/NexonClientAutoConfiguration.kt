package maple.nexon.client.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import maple.nexon.client.failure.NexonFailureClassifier
import maple.nexon.client.metrics.NexonClientMetrics
import maple.nexon.client.transport.NexonTransport
import maple.nexon.client.transport.NexonTransportFactory
import maple.nexon.client.transport.NexonTransportResources
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.reactive.function.client.WebClient

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    SystemNexonClientProperties::class,
    ByokNexonClientProperties::class,
    LegacyNexonApiProperties::class,
)
class NexonClientAutoConfiguration {
    @Bean
    fun nexonFailureClassifier(objectMapper: ObjectMapper): NexonFailureClassifier = NexonFailureClassifier(objectMapper)

    @Bean
    fun nexonClientMetrics(meterRegistry: ObjectProvider<MeterRegistry>): NexonClientMetrics = NexonClientMetrics(meterRegistry.ifAvailable)

    @Bean
    fun nexonTransportFactory(
        classifier: NexonFailureClassifier,
        metrics: NexonClientMetrics,
    ): NexonTransportFactory = NexonTransportFactory(classifier, metrics)

    @Bean("nexonSystemTransport")
    fun nexonSystemTransport(
        factory: NexonTransportFactory,
        systemProperties: SystemNexonClientProperties,
        byokProperties: ByokNexonClientProperties,
        legacyProperties: LegacyNexonApiProperties,
        environment: Environment,
    ): NexonTransport {
        val system = resolveSystemProperties(systemProperties, legacyProperties, environment)
        val byok = resolveByokProperties(byokProperties, legacyProperties, environment)
        NexonClientProfile.validateDistinctPoolNames(system, byok)
        return factory.create(NexonClientProfile.SYSTEM_BULK, system)
    }

    @Bean("nexonByokTransport")
    fun nexonByokTransport(
        factory: NexonTransportFactory,
        systemProperties: SystemNexonClientProperties,
        byokProperties: ByokNexonClientProperties,
        legacyProperties: LegacyNexonApiProperties,
        environment: Environment,
    ): NexonTransport {
        val system = resolveSystemProperties(systemProperties, legacyProperties, environment)
        val byok = resolveByokProperties(byokProperties, legacyProperties, environment)
        NexonClientProfile.validateDistinctPoolNames(system, byok)
        return factory.create(NexonClientProfile.USER_BYOK, byok)
    }

    @Bean("nexonSystemWebClient")
    fun nexonSystemWebClient(
        @Qualifier("nexonSystemTransport") transport: NexonTransport,
    ): WebClient = transport.webClient

    @Bean
    fun nexonTransportResources(
        @Qualifier("nexonSystemTransport") system: NexonTransport,
        @Qualifier("nexonByokTransport") byok: NexonTransport,
        metrics: NexonClientMetrics,
    ): NexonTransportResources = NexonTransportResources(
        systemProvider = system.provider,
        byokProvider = byok.provider,
        metrics = metrics,
    )

    companion object {
        fun resolveSystemProperties(
            properties: SystemNexonClientProperties,
            legacy: LegacyNexonApiProperties,
            environment: Environment,
        ): SystemNexonClientProperties = properties.copy(
            connectTimeoutMs = resolveConnectTimeout(
                current = properties.connectTimeoutMs,
                newKey = SYSTEM_CONNECT_TIMEOUT,
                legacy = legacy.connectTimeout,
                environment = environment,
            ),
            responseTimeoutSeconds = resolveResponseTimeout(
                current = properties.responseTimeoutSeconds,
                newKey = SYSTEM_RESPONSE_TIMEOUT,
                legacy = legacy.responseTimeout,
                environment = environment,
            ),
        ).validated()

        fun resolveByokProperties(
            properties: ByokNexonClientProperties,
            legacy: LegacyNexonApiProperties,
            environment: Environment,
        ): ByokNexonClientProperties = properties.copy(
            connectTimeoutMs = resolveConnectTimeout(
                current = properties.connectTimeoutMs,
                newKey = BYOK_CONNECT_TIMEOUT,
                legacy = legacy.connectTimeout,
                environment = environment,
            ),
            responseTimeoutSeconds = resolveResponseTimeout(
                current = properties.responseTimeoutSeconds,
                newKey = BYOK_RESPONSE_TIMEOUT,
                legacy = legacy.responseTimeout,
                environment = environment,
            ),
        ).validated()

        private fun resolveConnectTimeout(
            current: Int,
            newKey: String,
            legacy: Duration?,
            environment: Environment,
        ): Int = if (environment.containsProperty(newKey) || legacy == null) {
            current
        } else {
            legacy.toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private fun resolveResponseTimeout(
            current: Long,
            newKey: String,
            legacy: Duration?,
            environment: Environment,
        ): Long = if (environment.containsProperty(newKey) || legacy == null) {
            current
        } else {
            (legacy.toMillis() + 999L) / 1_000L
        }

        private const val SYSTEM_CONNECT_TIMEOUT = "nexon.http-client.connect-timeout-ms"
        private const val SYSTEM_RESPONSE_TIMEOUT = "nexon.http-client.response-timeout-seconds"
        private const val BYOK_CONNECT_TIMEOUT = "nexon.byok-http-client.connect-timeout-ms"
        private const val BYOK_RESPONSE_TIMEOUT = "nexon.byok-http-client.response-timeout-seconds"
    }
}
