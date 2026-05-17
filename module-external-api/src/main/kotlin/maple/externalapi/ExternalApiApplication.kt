package maple.externalapi

import maple.externalapi.config.NexonHttpClientProperties
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotEventProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["maple.externalapi", "maple.expectation.infrastructure.executor"])
@Import(maple.expectation.infrastructure.config.ExecutorConfig::class)
@EnableScheduling
@EnableConfigurationProperties(SnapshotChunkingProperties::class, SnapshotEventProperties::class, NexonHttpClientProperties::class)
class ExternalApiApplication

fun main(args: Array<String>) {
    org.springframework.boot.runApplication<ExternalApiApplication>(*args)
}
