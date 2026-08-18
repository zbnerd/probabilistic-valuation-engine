package maple.calculator

import maple.calculator.config.CalculatorEngineConfiguration
import maple.calculator.config.ExternalApiRunStatusProperties
import maple.calculator.config.PipelineProperties
import maple.pipeline.artifact.config.ArtifactStorageAutoConfiguration
import maple.pipeline.messaging.config.PipelineKafkaConsumerConfiguration
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(exclude = [SecurityAutoConfiguration::class, ManagementWebSecurityAutoConfiguration::class])
@EnableScheduling
@EnableConfigurationProperties(PipelineProperties::class, ExternalApiRunStatusProperties::class)
@Import(
    CalculatorEngineConfiguration::class,
    PipelineKafkaConsumerConfiguration::class,
    ArtifactStorageAutoConfiguration::class,
)
class CalculatorApplication

fun main(args: Array<String>) {
    runApplication<CalculatorApplication>(*args)
}
