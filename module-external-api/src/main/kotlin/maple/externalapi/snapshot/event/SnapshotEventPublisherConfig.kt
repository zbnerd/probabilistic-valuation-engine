package maple.externalapi.snapshot.event

import com.fasterxml.jackson.databind.ObjectMapper
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
    fun noOpSnapshotChunkEventPublisher(): SnapshotChunkEventPublisher =
        NoOpSnapshotChunkEventPublisher()

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
    ): SnapshotChunkEventPublisher =
        KafkaSnapshotChunkEventPublisher(
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper,
            chunkReadyTopic = properties.kafka.chunkReadyTopic,
            runCompletedTopic = properties.kafka.runCompletedTopic,
            runFailedTopic = properties.kafka.runFailedTopic,
        )

    @Bean
    @Qualifier("characterBasicSnapshotPublisher")
    fun characterBasicSnapshotPublisher(): SnapshotChunkEventPublisher =
        NoOpSnapshotChunkEventPublisher()
}
