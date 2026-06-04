package maple.externalapi

import maple.externalapi.config.NexonHttpClientProperties
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotEventProperties
import maple.expectation.infrastructure.config.MaplestoryApiConfig
import maple.expectation.infrastructure.config.TimeoutProperties
import maple.expectation.infrastructure.external.config.ExternalApiMetricsFilter
import maple.expectation.infrastructure.external.impl.RealNexonAuthClient
import maple.expectation.infrastructure.lifecycle.ManagedLifecycleCoordinator
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration
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
    ]
)
@Import(
    maple.expectation.infrastructure.config.CoreExecutorConfig::class,
    maple.expectation.infrastructure.config.VtExecutorConfig::class,
    MaplestoryApiConfig::class,
    ExternalApiMetricsFilter::class,
    RealNexonAuthClient::class,
    ManagedLifecycleCoordinator::class,
)
@EnableScheduling
@EnableConfigurationProperties(
    SnapshotChunkingProperties::class,
    SnapshotEventProperties::class,
    NexonHttpClientProperties::class,
    TimeoutProperties::class,
)
class ExternalApiApplication

fun main(args: Array<String>) {
    org.springframework.boot.runApplication<ExternalApiApplication>(*args)
}
