package maple.externalapi

import maple.expectation.infrastructure.lifecycle.ManagedLifecycleCoordinator
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
    scanBasePackages = [
        "maple.externalapi",
        "maple.expectation.infrastructure.executor",
    ],
    exclude = [
        SecurityAutoConfiguration::class,
        SecurityFilterAutoConfiguration::class,
        ManagementWebSecurityAutoConfiguration::class,
    ],
)
@Import(
    maple.expectation.infrastructure.config.CoreExecutorConfig::class,
    maple.expectation.infrastructure.config.VtExecutorConfig::class,
    ArtifactStorageAutoConfiguration::class,
    PipelineKafkaConsumerConfiguration::class,
    NexonClientAutoConfiguration::class,
    ManagedLifecycleCoordinator::class,
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
