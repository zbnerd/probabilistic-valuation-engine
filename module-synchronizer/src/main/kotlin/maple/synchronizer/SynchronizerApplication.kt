package maple.synchronizer

import maple.expectation.infrastructure.config.KafkaConsumerConfig
import maple.expectation.infrastructure.lifecycle.ManagedLifecycleCoordinator
import maple.synchronizer.consumer.ChunkExecutionProperties
import maple.synchronizer.ranking.EquipmentRankingProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication(
    scanBasePackages = [
        "maple.synchronizer",
        "maple.expectation.infrastructure.executor",
    ],
)
@EnableConfigurationProperties(EquipmentRankingProperties::class, ChunkExecutionProperties::class)
@Import(
    maple.expectation.infrastructure.config.CoreExecutorConfig::class,
    maple.expectation.infrastructure.config.VtExecutorConfig::class,
    maple.expectation.infrastructure.storage.StorageConfig::class,
    maple.expectation.infrastructure.storage.MinioHealthIndicator::class,
    KafkaConsumerConfig::class,
    ManagedLifecycleCoordinator::class,
)
class SynchronizerApplication

fun main(args: Array<String>) {
    runApplication<SynchronizerApplication>(*args)
}
