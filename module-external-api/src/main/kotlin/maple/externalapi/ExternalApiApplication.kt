package maple.externalapi

import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotEventProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SnapshotChunkingProperties::class, SnapshotEventProperties::class)
class ExternalApiApplication

fun main(args: Array<String>) {
    org.springframework.boot.runApplication<ExternalApiApplication>(*args)
}
