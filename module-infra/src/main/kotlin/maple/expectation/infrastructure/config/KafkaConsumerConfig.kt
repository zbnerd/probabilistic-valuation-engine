package maple.expectation.infrastructure.config

import maple.pipeline.messaging.config.PipelineKafkaConsumerConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/** Legacy import facade for non-ETL applications. */
@Configuration
@Import(PipelineKafkaConsumerConfiguration::class)
class KafkaConsumerConfig
