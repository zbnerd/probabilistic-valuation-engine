package maple.expectation.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app.kafka.pipeline")
data class KafkaPipelineProperties(
    @DefaultValue("false")
    val enabled: Boolean = false,

    @DefaultValue("1000")
    val outbox: OutboxProperties = OutboxProperties(),

    @DefaultValue("")
    val consumer: ConsumerProperties = ConsumerProperties(),
) {
    data class OutboxProperties(
        @DefaultValue("1000") val pollIntervalMs: Long = 1000,
        @DefaultValue("50") val batchSize: Int = 50,
        @DefaultValue("5") val maxRetryCount: Int = 5,
        @DefaultValue("10000") val retryDelayMs: Long = 10000,
    )

    data class ConsumerProperties(
        @DefaultValue("") val externalApi: ExternalApiConsumerProperties = ExternalApiConsumerProperties(),
        @DefaultValue("") val calculation: CalculationConsumerProperties = CalculationConsumerProperties(),
    )

    data class ExternalApiConsumerProperties(
        @DefaultValue("6") val concurrency: Int = 6,
        @DefaultValue("500") val pollTimeoutMs: Long = 500,
        @DefaultValue("10") val maxPollRecords: Int = 10,
        @DefaultValue("60") val leaseDurationSeconds: Long = 60,
    )

    data class CalculationConsumerProperties(
        @DefaultValue("4") val concurrency: Int = 4,
        @DefaultValue("500") val pollTimeoutMs: Long = 500,
        @DefaultValue("50") val maxPollRecords: Int = 50,
        @DefaultValue("300") val leaseDurationSeconds: Long = 300,
    )
}
