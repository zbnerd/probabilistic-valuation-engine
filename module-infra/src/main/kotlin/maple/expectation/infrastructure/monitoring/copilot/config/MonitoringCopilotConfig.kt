package maple.expectation.infrastructure.monitoring.copilot.config

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.infrastructure.config.DiscordTimeoutProperties
import maple.expectation.infrastructure.config.TimeoutProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.monitoring.copilot.client.PrometheusClient
import maple.expectation.infrastructure.monitoring.copilot.dedup.TimeBasedSlidingWindowStrategy
import maple.expectation.infrastructure.monitoring.copilot.detector.AnomalyDetector
import maple.expectation.infrastructure.monitoring.copilot.ingestor.GrafanaJsonIngestor
import maple.expectation.infrastructure.monitoring.copilot.notifier.DiscordNotifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.http.HttpClient

@Configuration
class MonitoringCopilotConfig(
    private val timeoutProperties: TimeoutProperties
) {
    companion object {
        private val log = LoggerFactory.getLogger(MonitoringCopilotConfig::class.java)
    }

    @Bean
    fun httpClient(): HttpClient {
        return HttpClient.newBuilder()
            .connectTimeout(timeoutProperties.apiCall)
            .build()
    }

    @Bean
    @ConditionalOnProperty(
        name = ["app.monitoring.enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun prometheusClient(
        httpClient: HttpClient,
        objectMapper: ObjectMapper,
        executor: LogicExecutor,
        @Value("\${app.monitoring.prometheus.base-url:http://localhost:9090}") prometheusUrl: String
    ): PrometheusClient {
        return PrometheusClient(httpClient, objectMapper, executor, prometheusUrl)
    }

    @Bean
    @ConditionalOnProperty(
        name = ["app.monitoring.enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun grafanaJsonIngestor(
        objectMapper: ObjectMapper,
        executor: LogicExecutor
    ): GrafanaJsonIngestor {
        return GrafanaJsonIngestor(objectMapper, executor)
    }

    @Bean
    @ConditionalOnProperty(
        name = ["app.monitoring.enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun anomalyDetector(): AnomalyDetector {
        return AnomalyDetector()
    }

    @Bean
    @ConditionalOnProperty(name = ["alert.discord.webhook-url"])
    fun discordNotifier(
        httpClient: HttpClient,
        objectMapper: ObjectMapper,
        executor: LogicExecutor,
        timeoutProperties: DiscordTimeoutProperties
    ): DiscordNotifier {
        return DiscordNotifier(httpClient, objectMapper, executor, timeoutProperties)
    }

    // Explicit bean definition for TimeBasedSlidingWindowStrategy
    // Note: This class also has @Component, Spring will use either this bean or component scanning
    @Bean
    @ConditionalOnProperty(name = ["monitoring.copilot.enabled"], havingValue = "true")
    fun timeBasedSlidingWindowStrategy(
        prometheusClient: PrometheusClient,
        executor: LogicExecutor,
        @Value("\${monitoring.copilot.dedup-window-minutes:10}") dedupWindowMinutes: Long
    ): TimeBasedSlidingWindowStrategy {
        return TimeBasedSlidingWindowStrategy(prometheusClient, executor, dedupWindowMinutes)
    }
}
