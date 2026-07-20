package maple.expectation.infrastructure.storage

import maple.pipeline.artifact.config.ArtifactStorageAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@Import(ArtifactStorageAutoConfiguration::class)
class StorageConfig
