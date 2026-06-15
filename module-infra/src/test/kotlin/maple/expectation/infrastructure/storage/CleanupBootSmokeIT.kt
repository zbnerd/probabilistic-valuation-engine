package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.test.context.TestPropertySource

@EnabledIfEnvironmentVariable(named = "INTEGRATION_MINIO", matches = "true")
@SpringBootTest(classes = [StorageConfig::class])
@EnableConfigurationProperties(MinioProperties::class)
@TestPropertySource(properties = [
    "storage.backend=minio",
    "storage.minio.endpoint=http://localhost:9000",
    "storage.minio.region=us-east-1",
    "storage.minio.bucket=maple-expectation",
    "storage.minio.access-key=cleanup",
    "storage.minio.secret-key=\${SA_CLEANUP_SECRET_KEY}",
])
@ComponentScan(
    basePackages = ["maple.expectation.infrastructure.storage"],
    useDefaultFilters = false,
    includeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [MinioHealthIndicator::class])]
)
class CleanupBootSmokeIT {

    @Autowired
    private lateinit var healthIndicator: MinioHealthIndicator

    @Test
    fun `cleanup boots and bucket validates`() {
        assertThat(healthIndicator.health().status.code).isEqualTo("UP")
    }
}
