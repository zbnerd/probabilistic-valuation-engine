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
import software.amazon.awssdk.services.s3.S3Client

/**
 * Spring boot smoke for the minio backend. Loads StorageConfig + MinioProperties
 * + LocalFsObjectStorage + MinioObjectStorage + MinioHealthIndicator beans
 * against a running MinIO. Verifies the @PostConstruct bucket validation
 * succeeds (i.e., the boot-time-fatal path does NOT fail when MinIO is reachable).
 *
 * Runs only when INTEGRATION_MINIO=true.
 */
@EnabledIfEnvironmentVariable(named = "INTEGRATION_MINIO", matches = "true")
@SpringBootTest(classes = [StorageConfig::class])
@EnableConfigurationProperties(MinioProperties::class)
@TestPropertySource(properties = [
    "storage.backend=minio",
    "storage.minio.endpoint=http://localhost:9000",
    "storage.minio.region=us-east-1",
    "storage.minio.access-key=maple",
    "storage.minio.secret-key=changeme",
    "storage.minio.bucket=maple-expectation",
])
@ComponentScan(
    basePackages = ["maple.expectation.infrastructure.storage"],
    useDefaultFilters = false,
    includeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [MinioHealthIndicator::class])]
)
class MinioBootSmokeIT {

    @Autowired
    private lateinit var s3: S3Client

    @Autowired(required = false)
    private lateinit var healthIndicator: MinioHealthIndicator

    @Test
    fun `s3 client bean is wired`() {
        assertThat(s3).isNotNull
    }

    @Test
    fun `health indicator bean is wired and reports UP`() {
        assertThat(healthIndicator).isNotNull
        val health = healthIndicator.health()
        assertThat(health.status.code).isEqualTo("UP")
    }
}
