package maple.expectation.infrastructure.config

import maple.expectation.core.port.out.KafkaTopicNames
import org.apache.kafka.clients.admin.NewTopic
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
@ConditionalOnProperty(prefix = "app.kafka.pipeline", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(KafkaPipelineProperties::class)
class KafkaPipelineConfig(
    private val properties: KafkaPipelineProperties,
) {
    private val log = LoggerFactory.getLogger(KafkaPipelineConfig::class.java)

    @Bean
    fun externalApiRequestedTopic(): NewTopic = TopicBuilder.name(KafkaTopicNames.EXTERNAL_API_REQUESTED)
        .partitions(6)
        .replicas(1)
        .compact()
        .build()
        .also { log.info("[KafkaPipeline] Topic bean registered: {}", KafkaTopicNames.EXTERNAL_API_REQUESTED) }

    @Bean
    fun externalApiRequestedDltTopic(): NewTopic = TopicBuilder.name(KafkaTopicNames.EXTERNAL_API_REQUESTED_DLT)
        .partitions(1)
        .replicas(1)
        .build()

    @Bean
    fun calculationRequestedTopic(): NewTopic = TopicBuilder.name(KafkaTopicNames.CALCULATION_REQUESTED)
        .partitions(properties.consumer.calculation.concurrency)
        .replicas(1)
        .compact()
        .build()
        .also { log.info("[KafkaPipeline] Topic bean registered: {}", KafkaTopicNames.CALCULATION_REQUESTED) }

    @Bean
    fun calculationRequestedDltTopic(): NewTopic = TopicBuilder.name(KafkaTopicNames.CALCULATION_REQUESTED_DLT)
        .partitions(1)
        .replicas(1)
        .build()
}
