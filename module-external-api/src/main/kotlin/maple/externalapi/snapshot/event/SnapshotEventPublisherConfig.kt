package maple.externalapi.snapshot.event

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.metrics.SchedulerMetrics
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate

@Configuration
class SnapshotEventPublisherConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(
        prefix = "external-api.snapshot.events.kafka",
        name = ["enabled"],
        havingValue = "false",
        matchIfMissing = true,
    )
    fun noOpSnapshotChunkEventPublisher(): SnapshotChunkEventPublisher = NoOpSnapshotChunkEventPublisher()

    @Bean
    @Primary
    @ConditionalOnProperty(
        prefix = "external-api.snapshot.events.kafka",
        name = ["enabled"],
        havingValue = "true",
    )
    fun kafkaSnapshotChunkEventPublisher(
        kafkaTemplate: KafkaTemplate<String, String>,
        objectMapper: ObjectMapper,
        properties: SnapshotEventProperties,
        schedulerMetrics: SchedulerMetrics,
    ): SnapshotChunkEventPublisher = KafkaSnapshotChunkEventPublisher(
        kafkaTemplate = kafkaTemplate,
        objectMapper = objectMapper,
        chunkReadyTopic = properties.kafka.chunkReadyTopic,
        runCompletedTopic = properties.kafka.runCompletedTopic,
        runFailedTopic = properties.kafka.runFailedTopic,
        schedulerMetrics = schedulerMetrics,
    )

    @Bean
    @Qualifier("characterBasicSnapshotPublisher")
    @ConditionalOnProperty(
        prefix = "external-api.snapshot.events.kafka",
        name = ["enabled"],
        havingValue = "false",
        matchIfMissing = true,
    )
    fun noOpCharacterBasicSnapshotPublisher(): SnapshotChunkEventPublisher = NoOpSnapshotChunkEventPublisher()

    @Bean
    @Qualifier("characterBasicSnapshotPublisher")
    @ConditionalOnProperty(
        prefix = "external-api.snapshot.events.kafka",
        name = ["enabled"],
        havingValue = "true",
    )
    fun kafkaCharacterBasicSnapshotPublisher(
        kafkaTemplate: KafkaTemplate<String, String>,
        objectMapper: ObjectMapper,
        properties: SnapshotEventProperties,
        schedulerMetrics: SchedulerMetrics,
    ): SnapshotChunkEventPublisher = KafkaSnapshotChunkEventPublisher(
        kafkaTemplate = kafkaTemplate,
        objectMapper = objectMapper,
        chunkReadyTopic = properties.kafka.chunkReadyTopic,
        runCompletedTopic = properties.kafka.runCompletedTopic,
        runFailedTopic = properties.kafka.runFailedTopic,
        schedulerMetrics = schedulerMetrics,
    )

    @Bean
    @Qualifier("rankingSnapshotPublisher")
    @ConditionalOnProperty(
        prefix = "external-api.snapshot.events.kafka",
        name = ["enabled"],
        havingValue = "false",
        matchIfMissing = true,
    )
    fun noOpRankingSnapshotPublisher(): SnapshotChunkEventPublisher = NoOpSnapshotChunkEventPublisher()

    @Bean
    @Qualifier("rankingSnapshotPublisher")
    @ConditionalOnProperty(
        prefix = "external-api.snapshot.events.kafka",
        name = ["enabled"],
        havingValue = "true",
    )
    fun kafkaRankingSnapshotPublisher(
        kafkaTemplate: KafkaTemplate<String, String>,
        objectMapper: ObjectMapper,
        properties: SnapshotEventProperties,
        schedulerMetrics: SchedulerMetrics,
    ): SnapshotChunkEventPublisher = KafkaSnapshotChunkEventPublisher(
        kafkaTemplate = kafkaTemplate,
        objectMapper = objectMapper,
        chunkReadyTopic = properties.kafka.chunkReadyTopic,
        runCompletedTopic = properties.kafka.runCompletedTopic,
        runFailedTopic = properties.kafka.runFailedTopic,
        schedulerMetrics = schedulerMetrics,
    )

    @Bean
    @Qualifier("ocidLookupSnapshotPublisher")
    @ConditionalOnProperty(
        prefix = "external-api.snapshot.events.kafka",
        name = ["enabled"],
        havingValue = "false",
        matchIfMissing = true,
    )
    fun noOpOcidLookupSnapshotPublisher(): SnapshotChunkEventPublisher = NoOpSnapshotChunkEventPublisher()

    @Bean
    @Qualifier("ocidLookupSnapshotPublisher")
    @ConditionalOnProperty(
        prefix = "external-api.snapshot.events.kafka",
        name = ["enabled"],
        havingValue = "true",
    )
    fun kafkaOcidLookupSnapshotPublisher(
        kafkaTemplate: KafkaTemplate<String, String>,
        objectMapper: ObjectMapper,
        properties: SnapshotEventProperties,
        schedulerMetrics: SchedulerMetrics,
    ): SnapshotChunkEventPublisher = KafkaSnapshotChunkEventPublisher(
        kafkaTemplate = kafkaTemplate,
        objectMapper = objectMapper,
        chunkReadyTopic = properties.kafka.ocidLookupTopic,
        runCompletedTopic = properties.kafka.ocidLookupTopic,
        runFailedTopic = properties.kafka.runFailedTopic,
        schedulerMetrics = schedulerMetrics,
    )
}
