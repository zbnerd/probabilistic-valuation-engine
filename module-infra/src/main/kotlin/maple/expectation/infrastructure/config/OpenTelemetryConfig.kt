package maple.expectation.infrastructure.config

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.contrib.sampler.RuleBasedRoutingSampler
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider
import io.opentelemetry.semconv.UrlAttributes
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = ["management.tracing.enabled"], havingValue = "true")
class OpenTelemetryConfig {

    private val log = LoggerFactory.getLogger(OpenTelemetryConfig::class.java)

    @Bean
    fun otelCustomizer(): AutoConfigurationCustomizerProvider {
        log.info("OpenTelemetry RuleBasedRoutingSampler configuration")

        return AutoConfigurationCustomizerProvider { provider ->
            provider.addSamplerCustomizer { fallback, _ ->
                RuleBasedRoutingSampler.builder(SpanKind.SERVER, fallback)
                    .drop(UrlAttributes.URL_PATH, "^/actuator.*")
                    .drop(UrlAttributes.URL_PATH, "^/health.*")
                    .drop(UrlAttributes.URL_PATH, "^/swagger-ui.*")
                    .drop(UrlAttributes.URL_PATH, "^/v3/api-docs.*")
                    .build()
            }
        }
    }
}
