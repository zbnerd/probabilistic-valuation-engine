package maple.externalapi

import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotEventProperties
import maple.nexon.client.config.NexonClientAutoConfiguration
import maple.pipeline.artifact.config.ArtifactStorageAutoConfiguration
import maple.pipeline.messaging.config.PipelineKafkaConsumerConfiguration
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(
    scanBasePackages = ["maple.externalapi"],
    exclude = [
        SecurityAutoConfiguration::class,
        SecurityFilterAutoConfiguration::class,
        ManagementWebSecurityAutoConfiguration::class,
    ],
)
@Import(
    ArtifactStorageAutoConfiguration::class,
    PipelineKafkaConsumerConfiguration::class,
    NexonClientAutoConfiguration::class,
)
@EnableScheduling
@EnableConfigurationProperties(
    SnapshotChunkingProperties::class,
    SnapshotEventProperties::class,
)
class ExternalApiApplication

fun main(args: Array<String>) {
    org.springframework.boot.runApplication<ExternalApiApplication>(*args)
}
