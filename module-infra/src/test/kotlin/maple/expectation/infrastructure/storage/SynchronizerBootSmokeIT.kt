package maple.expectation.infrastructure.storage

import maple.pipeline.artifact.config.ArtifactStorageHealthIndicator
import maple.pipeline.artifact.storage.MinioProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@EnabledIfEnvironmentVariable(named = "INTEGRATION_MINIO", matches = "true")
@SpringBootTest(classes = [StorageConfig::class])
@EnableConfigurationProperties(MinioProperties::class)
@TestPropertySource(
    properties = [
        "storage.backend=minio",
        "storage.minio.endpoint=http://localhost:9000",
        "storage.minio.region=us-east-1",
        "storage.minio.bucket=maple-expectation",
        "storage.minio.access-key=synchronizer",
        "storage.minio.secret-key=\${SA_SYNCHRONIZER_SECRET_KEY}",
    ],
)
class SynchronizerBootSmokeIT {

    @Autowired
    private lateinit var healthIndicator: ArtifactStorageHealthIndicator

    @Test
    fun `synchronizer boots and bucket validates`() {
        assertThat(healthIndicator.health().status.code).isEqualTo("UP")
    }
}
