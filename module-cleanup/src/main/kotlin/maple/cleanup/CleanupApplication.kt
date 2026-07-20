package maple.cleanup

import maple.cleanup.config.CleanupProperties
import maple.cleanup.inbox.InboxProperties
import maple.pipeline.artifact.config.ArtifactStorageAutoConfiguration
import maple.pipeline.messaging.config.PipelineKafkaConsumerConfiguration
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication(
    exclude = [
        SecurityAutoConfiguration::class,
        ManagementWebSecurityAutoConfiguration::class,
    ],
)
@Import(PipelineKafkaConsumerConfiguration::class, ArtifactStorageAutoConfiguration::class)
@EnableConfigurationProperties(CleanupProperties::class, InboxProperties::class)
class CleanupApplication

fun main(args: Array<String>) {
    runApplication<CleanupApplication>(*args)
}
