package maple.synchronizer

import maple.pipeline.artifact.config.ArtifactStorageAutoConfiguration
import maple.pipeline.messaging.config.PipelineKafkaConsumerConfiguration
import maple.synchronizer.consumer.ChunkExecutionProperties
import maple.synchronizer.ranking.EquipmentRankingProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication(scanBasePackages = ["maple.synchronizer"])
@EnableConfigurationProperties(EquipmentRankingProperties::class, ChunkExecutionProperties::class)
@Import(
    ArtifactStorageAutoConfiguration::class,
    PipelineKafkaConsumerConfiguration::class,
)
class SynchronizerApplication

fun main(args: Array<String>) {
    runApplication<SynchronizerApplication>(*args)
}
